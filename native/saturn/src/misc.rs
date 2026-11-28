//! fold, and plain answers for tools Android doesn't have (so the model's next try works).

use std::io::{Read, Write};
use std::process::ExitCode;

pub fn no_python(name: &str) -> ExitCode {
    eprintln!("{name}: Saturn runs on Android and has no Python. Use node (JavaScript) instead:
  node -e 'console.log(require(\"fs\").readFileSync(\"file.txt\", \"utf8\").length)'
  node -p '2n ** 64n'            # quick maths (BigInt for big numbers)
  npm i -g <package>             # libraries; require() them from anywhere");
    ExitCode::from(127)
}

pub fn no_apt(name: &str) -> ExitCode {
    eprintln!("{name}: Saturn runs on Android: there is no apt/pkg. Command-line tools come from npm (npm i -g <pkg>); for scripts use node.");
    ExitCode::from(127)
}

/// fold [-w N] [-s] [file…]: wrap lines to N columns (80); -s breaks at the last space.
pub fn fold(args: &[String]) -> ExitCode {
    let (mut width, mut spaces, mut files) = (80usize, false, Vec::new());
    let mut i = 0;
    while i < args.len() {
        let a = &args[i];
        match a.as_str() {
            "-s" => spaces = true,
            "-w" => { i += 1; width = args.get(i).and_then(|w| w.parse().ok()).unwrap_or(80) }
            _ if a.starts_with("-w") => width = a[2..].parse().unwrap_or(80),
            _ if a.starts_with('-') && a[1..].chars().all(|c| c.is_ascii_digit()) && a.len() > 1 => width = a[1..].parse().unwrap_or(80),
            _ if a.starts_with('-') && a.len() > 1 && a[1..].chars().all(|c| c == 's' || c == 'b') => spaces |= a.contains('s'),
            _ => files.push(a.clone()),
        }
        i += 1;
    }
    let width = width.max(1);
    let mut text = String::new();
    if files.is_empty() { let _ = std::io::stdin().read_to_string(&mut text); }
    for f in &files {
        match std::fs::read_to_string(f) {
            Ok(t) => text.push_str(&t),
            Err(e) => { eprintln!("fold: {f}: {e}"); return ExitCode::from(1) }
        }
    }
    let mut out = std::io::BufWriter::new(std::io::stdout().lock());
    for (n, line) in text.split('\n').enumerate() {
        if n > 0 { let _ = out.write_all(b"\n"); }
        let chars: Vec<char> = line.chars().collect();
        let mut start = 0;
        while chars.len() - start > width {
            let mut end = start + width;
            if spaces {
                if let Some(sp) = chars[start..end].iter().rposition(|c| *c == ' ') { if sp > 0 { end = start + sp + 1; } }
            }
            let _ = writeln!(out, "{}", chars[start..end].iter().collect::<String>());
            start = end;
        }
        let _ = out.write_all(chars[start..].iter().collect::<String>().as_bytes());
    }
    ExitCode::SUCCESS
}
