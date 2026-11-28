'use strict';
// better-sqlite3 on Node's built-in SQLite (node:sqlite), for when its compiled part can't be built on the
// phone. Covers the API extensions use: prepare/run/get/all/iterate, pluck/raw/expand, exec, pragma,
// transaction (with savepoints when nested), function, close. See ../saturn-compat.cjs.
// Node warns that its SQLite is experimental; that's noise in an extension's output.
const emit = process.emitWarning;
process.emitWarning = function (w, ...a) {
  if (String((w && w.message) || w).includes('SQLite is an experimental feature')) return;
  return emit.call(process, w, ...a);
};
const { DatabaseSync } = require('node:sqlite');
process.emitWarning = emit;

class SqliteError extends Error {
  constructor(message, code) { super(message); this.name = 'SqliteError'; this.code = code; }
}

function wrap(fn) {
  try { return fn(); } catch (e) {
    if (e && e.code === 'ERR_SQLITE_ERROR') throw new SqliteError(e.message, e.errstr || 'SQLITE_ERROR');
    throw e;
  }
}

// better-sqlite3 binds a plain object to named parameters and spreads arrays; booleans aren't allowed there
// either, so values pass through as they are.
function params(args) {
  if (args.length === 1 && Array.isArray(args[0])) return args[0];
  return args;
}

class Statement {
  constructor(db, source) {
    this.database = db;
    this.source = source;
    this._st = wrap(() => db._db.prepare(source));
    this._pluck = false; this._raw = false; this._expand = false; this._bound = null;
    this.reader = /^\s*(select|pragma|with|values|explain)\b/i.test(source) || /\breturning\b/i.test(source);
  }
  _args(a) { return this._bound ? this._bound : params(a); }
  _row(r) {
    if (r === undefined || r === null) return undefined;
    if (this._pluck) return Object.values(r)[0];
    if (this._raw) return Object.values(r);
    return { ...r }; // node:sqlite rows have no prototype; better-sqlite3's are plain objects
  }
  run(...a) {
    const r = wrap(() => this._st.run(...this._args(a)));
    return { changes: Number(r.changes), lastInsertRowid: r.lastInsertRowid };
  }
  get(...a) { return this._row(wrap(() => this._st.get(...this._args(a)))); }
  all(...a) { return wrap(() => this._st.all(...this._args(a))).map(r => this._row(r)); }
  *iterate(...a) { for (const r of wrap(() => this._st.iterate(...this._args(a)))) yield this._row(r); }
  pluck(on = true) { this._pluck = on; if (on) this._raw = false; return this; }
  raw(on = true) { this._raw = on; if (on) this._pluck = false; return this; }
  expand(on = true) { this._expand = on; return this; }
  safeIntegers(on = true) { if (this._st.setReadBigInts) this._st.setReadBigInts(on); return this; }
  bind(...a) { this._bound = params(a); return this; }
  columns() {
    if (this._st.columns) return this._st.columns().map(c => ({ name: c.name, column: c.column, table: c.table, database: c.database, type: c.type }));
    return [];
  }
}

class Database {
  constructor(filename = ':memory:', options = {}) {
    if (filename === '' || filename == null) filename = ':memory:';
    if (options.fileMustExist && filename !== ':memory:' && !require('fs').existsSync(filename))
      throw new SqliteError(`unable to open database file: ${filename}`, 'SQLITE_CANTOPEN');
    this.name = filename;
    this.memory = filename === ':memory:';
    this.readonly = !!options.readonly;
    this._db = wrap(() => new DatabaseSync(filename, { readOnly: this.readonly, allowExtension: true }));
    this._depth = 0;
    this.open = true;
  }
  get inTransaction() { return this._db.isTransaction !== undefined ? this._db.isTransaction : this._depth > 0; }
  prepare(source) { return new Statement(this, source); }
  exec(source) { wrap(() => this._db.exec(source)); return this; }
  pragma(source, options = {}) {
    const st = this.prepare(`PRAGMA ${source}`);
    return options.simple ? st.pluck().get() : st.all();
  }
  function(name, options, fn) {
    if (typeof options === 'function') { fn = options; options = {}; }
    this._db.function(name, { deterministic: !!options.deterministic, varargs: !!options.varargs }, fn);
    return this;
  }
  aggregate(name, options) {
    if (!this._db.aggregate) throw new Error('aggregate() is not available in this Node version');
    this._db.aggregate(name, options); return this;
  }
  loadExtension(file) { this._db.loadExtension(file); return this; }
  transaction(fn) {
    const db = this;
    const make = (begin) => function (...a) {
      if (db._depth > 0) {
        const sp = `saturn_sp_${db._depth}`;
        db._db.exec(`SAVEPOINT ${sp}`); db._depth++;
        try { const r = fn.apply(this, a); db._depth--; db._db.exec(`RELEASE ${sp}`); return r; }
        catch (e) { db._depth--; db._db.exec(`ROLLBACK TO ${sp}`); db._db.exec(`RELEASE ${sp}`); throw e; }
      }
      db._db.exec(begin); db._depth++;
      try { const r = fn.apply(this, a); db._depth--; db._db.exec('COMMIT'); return r; }
      catch (e) { db._depth--; if (db.inTransaction) db._db.exec('ROLLBACK'); throw e; }
    };
    const t = make('BEGIN');
    t.deferred = make('BEGIN DEFERRED'); t.immediate = make('BEGIN IMMEDIATE'); t.exclusive = make('BEGIN EXCLUSIVE');
    t.default = t;
    return t;
  }
  defaultSafeIntegers() { return this; }
  unsafeMode() { return this; }
  close() { if (this.open) { this._db.close(); this.open = false; } return this; }
}

Database.SqliteError = SqliteError;
Database.Database = Database;
module.exports = Database;
module.exports.default = Database;
module.exports.SqliteError = SqliteError;
