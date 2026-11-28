//! jq, through jaq: the whole jq language, run natively.
//! Options: -r -j -c -n -s -e -S --tab --indent N --arg NAME V --argjson NAME JSON, and files (stdin by default).

use jaq_core::load::{Arena, File, Loader};
use jaq_core::{data, unwrap_valr, Compiler, Ctx, Vars};
use jaq_json::{read, write, Val};
use std::io::{Read, Write};
use std::process::ExitCode;

pub fn main(args: &[String]) -> ExitCode {
    let (mut raw, mut join, mut compact, mut null_input, mut slurp, mut exit_status, mut sort) =
        (false, false, false, false, false, false, false);
    let mut indent = "  ".to_string();
    let mut filter: Option<String> = None;
    let mut files = Vec::new();
    let mut var_names: Vec<String> = Vec::new();
    let mut var_vals: Vec<Val> = Vec::new();
    let mut i = 0;
    while i < args.len() {
        let a = args[i].as_str();
        match a {
            "-r" | "--raw-output" => raw = true,
            "-j" | "--join-output" => { raw = true; join = true }
            "-c" | "--compact-output" => compact = true,
            "-n" | "--null-input" => null_input = true,
            "-s" | "--slurp" => slurp = true,
            "-e" | "--exit-status" => exit_status = true,
            "-S" | "--sort-keys" => sort = true,
            "--tab" => indent = "\t".into(),
            "--indent" => { i += 1; indent = " ".repeat(args.get(i).and_then(|n| n.parse().ok()).unwrap_or(2)) }
            "--arg" | "--argjson" => {
                let (Some(n), Some(v)) = (args.get(i + 1), args.get(i + 2)) else { return fail(&format!("jq: {a} needs a name and a value")) };
                let val = if a == "--arg" { Val::from(v.clone()) } else {
                    match read::parse_single(v.as_bytes()) { Ok(v) => v, Err(e) => return fail(&format!("jq: --argjson {n}: {e}")) }
                };
                var_names.push(format!("${n}")); var_vals.push(val);
                i += 2;
            }
            _ if a.len() > 1 && a.starts_with('-') && !a.starts_with("--") && a[1..].chars().all(|c| "rjcnseS".contains(c)) => {
                for c in a[1..].chars() {
                    match c { 'r' => raw = true, 'j' => { raw = true; join = true } 'c' => compact = true, 'n' => null_input = true,
                              's' => slurp = true, 'e' => exit_status = true, 'S' => sort = true, _ => {} }
                }
            }
            _ if a.starts_with('-') && a != "-" => return fail(&format!("jq: unknown option {a}")),
            _ => if filter.is_none() { filter = Some(a.to_string()) } else { files.push(a.to_string()) },
        }
        i += 1;
    }
    let code = filter.unwrap_or_else(|| ".".into());

    // Compile.
    let defs = jaq_core::defs().chain(jaq_std::defs()).chain(jaq_json::defs());
    let funs = jaq_core::funs().chain(jaq_std::funs()).chain(jaq_json::funs());
    let loader = Loader::new(defs);
    let arena = Arena::default();
    let modules = match loader.load(&arena, File { code: code.as_str(), path: () }) {
        Ok(m) => m,
        Err(errs) => return fail(&format!("jq: error: couldn't parse the filter {code:?} ({} problem(s))", errs.len())),
    };
    let names: Vec<&str> = var_names.iter().map(|s| s.as_str()).collect();
    let compiled = match Compiler::default().with_funs(funs).with_global_vars(names.iter().copied()).compile(modules) {
        Ok(f) => f,
        Err(errs) => return fail(&format!("jq: error: couldn't compile the filter {code:?} ({} problem(s); unknown function or variable?)", errs.len())),
    };

    // Inputs.
    let mut bytes = Vec::new();
    // -n reads nothing (jq's `input` isn't supported); -n -s still slurps like jq.
    if files.is_empty() && (!null_input || slurp) { let _ = std::io::stdin().read_to_end(&mut bytes); }
    for f in &files {
        match std::fs::read(f) { Ok(b) => { bytes.extend_from_slice(&b); bytes.push(b'\n') } Err(e) => return fail(&format!("jq: {f}: {e}")) }
    }
    let mut inputs: Vec<Val> = Vec::new();
    if !null_input || slurp {
        for v in read::parse_many(&bytes) {
            match v { Ok(v) => inputs.push(v), Err(e) => return fail(&format!("jq: error: invalid JSON input: {e}")) }
        }
    }
    let inputs: Vec<Val> = if slurp { vec![inputs.into_iter().collect::<Val>()] } else if null_input { vec![Val::Null] } else { inputs };

    // Run and print.
    let pp = write::Pp { indent: if compact { None } else { Some(indent) }, sort_keys: sort, styles: Default::default(), sep_space: !compact };
    let mut out = std::io::BufWriter::new(std::io::stdout().lock());
    let mut last: Option<bool> = None;
    for input in inputs {
        let ctx = Ctx::<data::JustLut<Val>>::new(&compiled.lut, Vars::new(var_vals.iter().cloned()));
        for r in compiled.id.run((ctx, input)).map(unwrap_valr) {
            match r {
                Ok(v) => {
                    last = Some(!matches!(v, Val::Null | Val::Bool(false)));
                    let res = match &v {
                        Val::TStr(s) | Val::BStr(s) if raw => out.write_all(s),
                        _ => write::write(&mut out, &pp, 0, &v),
                    };
                    if res.is_err() { return ExitCode::SUCCESS } // reader went away (e.g. `| head`)
                    if !join { let _ = out.write_all(b"\n"); }
                }
                Err(e) => { let _ = out.flush(); eprintln!("jq: error: {e}"); return ExitCode::from(5) }
            }
        }
    }
    let _ = out.flush();
    if exit_status { return match last { Some(true) => ExitCode::SUCCESS, Some(false) => ExitCode::from(1), None => ExitCode::from(4) } }
    ExitCode::SUCCESS
}

fn fail(msg: &str) -> ExitCode { eprintln!("{msg}"); ExitCode::from(2) }
