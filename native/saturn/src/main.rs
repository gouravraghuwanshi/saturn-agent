//! Saturn's small native tools, in one binary picked by the name it's run as (argv[0]):
//!
//! * the phone commands (`termux-torch`, `saturn-apps`, …): sends the arguments to the Saturn app's local
//!   bridge and prints its answer. This used to be a shell script plus curl for every call.
//! * `jq`: the real jq language (jaq), instead of a small JavaScript subset that needed a whole Node.
//! * `fold`, and the hints for `python`, `pip`, `apt` (there are none on Android).
//!
//! Saturn ships it as `libx_saturn.so` and links each command name to it.

mod bridge;
mod jq;
mod misc;

use std::process::ExitCode;

fn main() -> ExitCode {
    let mut args: Vec<String> = std::env::args().collect();
    let argv0 = args.first().cloned().unwrap_or_default();
    let mut name = std::path::Path::new(&argv0).file_name().and_then(|s| s.to_str()).unwrap_or("").to_string();
    // Run as itself (`saturn jq …`, or the library file): the tool is the first argument.
    if name == "saturn" || name.starts_with("libx_saturn") {
        if args.len() < 2 {
            eprintln!("usage: saturn <tool> [args…]");
            return ExitCode::from(2);
        }
        name = args.remove(1);
    }
    let rest = &args[1..];
    match name.as_str() {
        "jq" => jq::main(rest),
        "fold" => misc::fold(rest),
        "python" | "python3" | "pip" | "pip3" => misc::no_python(&name),
        "apt" | "apt-get" | "pkg" => misc::no_apt(&name),
        _ => bridge::main(&name, rest),
    }
}
