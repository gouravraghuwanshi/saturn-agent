//! Phone commands: one HTTP request to the Saturn app's local bridge ($PIMOBILE_BROWSER_URL), the same
//! routes the old shell shims used with curl. No TLS (it's 127.0.0.1) and no dependencies.

use std::io::{IsTerminal, Read, Write};
use std::net::TcpStream;
use std::process::ExitCode;

/// What a command sends, and where.
enum Route {
    /// /app/device/<name>: cwd \0 artifacts \0 args…
    Device,
    /// /app/schedule: cwd \0 artifacts \0 args…
    Schedule,
    /// /app/open: the URL
    Open,
    /// POST /app/clipboard: the text
    ClipSet,
    /// GET /app/clipboard
    ClipGet,
}

fn route(name: &str) -> Route {
    match name {
        "saturn-schedule" => Route::Schedule,
        "termux-open-url" | "xdg-open" => Route::Open,
        "termux-clipboard-set" => Route::ClipSet,
        "termux-clipboard-get" => Route::ClipGet,
        _ => Route::Device,
    }
}

fn stdin_text() -> Option<String> {
    let mut stdin = std::io::stdin();
    if stdin.is_terminal() { return None; }
    let mut s = String::new();
    stdin.read_to_string(&mut s).ok()?;
    Some(s)
}

pub fn main(name: &str, args: &[String]) -> ExitCode {
    let Ok(base) = std::env::var("PIMOBILE_BROWSER_URL") else {
        eprintln!("{name}: not running inside Saturn");
        return ExitCode::from(1);
    };
    let mut args = args.to_vec();
    let (method, path, body): (&str, String, Vec<u8>) = match route(name) {
        Route::Device | Route::Schedule => {
            // Piped text when called without arguments: `echo hi | termux-tts-speak`.
            if args.is_empty() {
                match name {
                    "termux-tts-speak" | "termux-toast" => if let Some(t) = stdin_text() { args.push(t) },
                    "termux-share" => if let Some(t) = stdin_text() { args = vec!["--text".into(), t] },
                    _ => {}
                }
            }
            let cwd = std::env::current_dir().map(|p| p.display().to_string()).unwrap_or_default();
            let artifacts = std::env::var("SATURN_ARTIFACTS").unwrap_or_default();
            let mut body = Vec::new();
            for part in [cwd, artifacts].iter().chain(args.iter()) {
                body.extend_from_slice(part.as_bytes());
                body.push(0);
            }
            let path = match route(name) { Route::Schedule => "/app/schedule".into(), _ => format!("/app/device/{name}") };
            ("POST", path, body)
        }
        Route::Open => ("POST", "/app/open".into(), args.first().cloned().unwrap_or_default().into_bytes()),
        Route::ClipSet => {
            let text = if args.is_empty() { stdin_text().unwrap_or_default() } else { args.join(" ") };
            ("POST", "/app/clipboard".into(), text.into_bytes())
        }
        Route::ClipGet => ("GET", "/app/clipboard".into(), Vec::new()),
    };
    match request(&base, method, &path, &body) {
        Ok((status, out)) if (200..300).contains(&status) => {
            let out = String::from_utf8_lossy(&out);
            if !out.is_empty() && !matches!(route(name), Route::Open | Route::ClipSet) {
                // The bridge's answer, as the shims printed it: ending in one newline (clipboard text as is).
                if matches!(route(name), Route::ClipGet) { print!("{out}") } else { println!("{}", out.trim_end_matches('\n')) }
            }
            ExitCode::SUCCESS
        }
        Ok((_, out)) => {
            let msg = String::from_utf8_lossy(&out);
            eprintln!("{name}: {}", if msg.trim().is_empty() { "Saturn is not responding" } else { msg.trim_end() });
            ExitCode::from(1)
        }
        Err(e) => {
            eprintln!("{name}: Saturn is not responding ({e})");
            ExitCode::from(1)
        }
    }
}

/// One HTTP/1.1 request to http://host:port/prefix + path. Returns the status and body.
fn request(base: &str, method: &str, path: &str, body: &[u8]) -> std::io::Result<(u16, Vec<u8>)> {
    let bad = |m: &str| std::io::Error::new(std::io::ErrorKind::InvalidData, m.to_string());
    let rest = base.strip_prefix("http://").ok_or_else(|| bad("PIMOBILE_BROWSER_URL isn't http://"))?;
    let (host, prefix) = rest.split_once('/').map(|(h, p)| (h, format!("/{}", p.trim_end_matches('/')))).unwrap_or((rest, String::new()));
    let mut s = TcpStream::connect(host)?;
    let _ = s.set_nodelay(true);
    let head = format!(
        "{method} {prefix}{path} HTTP/1.1\r\nHost: {host}\r\nContent-Length: {}\r\nContent-Type: application/octet-stream\r\nConnection: close\r\n\r\n",
        body.len()
    );
    s.write_all(head.as_bytes())?;
    s.write_all(body)?;
    s.flush()?;
    let mut raw = Vec::new();
    s.read_to_end(&mut raw)?;
    let split = raw.windows(4).position(|w| w == b"\r\n\r\n").ok_or_else(|| bad("bad answer from Saturn"))?;
    let head = String::from_utf8_lossy(&raw[..split]).to_string();
    let mut body = raw[split + 4..].to_vec();
    let status = head.split_whitespace().nth(1).and_then(|c| c.parse().ok()).ok_or_else(|| bad("bad status from Saturn"))?;
    if head.to_ascii_lowercase().contains("transfer-encoding: chunked") { body = dechunk(&body); }
    Ok((status, body))
}

fn dechunk(mut b: &[u8]) -> Vec<u8> {
    let mut out = Vec::new();
    while let Some(i) = b.windows(2).position(|w| w == b"\r\n") {
        let size = usize::from_str_radix(String::from_utf8_lossy(&b[..i]).split(';').next().unwrap_or("0").trim(), 16).unwrap_or(0);
        if size == 0 || b.len() < i + 2 + size { break; }
        out.extend_from_slice(&b[i + 2..i + 2 + size]);
        b = &b[(i + 4 + size).min(b.len())..];
    }
    out
}
