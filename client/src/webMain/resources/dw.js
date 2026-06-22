const SQLITE_DIR = new URL("./sqlite/", self.location.href).href
const SQLITE_JS = `${SQLITE_DIR}sqlite3.js`

let sqlite3Promise
let persistentDbFactoryPromise
let nextDatabaseId = 1
let nextStatementId = 1

const databases = new Map()
const statements = new Map()

function reply(id, data) { postMessage({ id, data }) }

function replyError(id, error) { postMessage({ id, error: error?.message || String(error) }) }

async function sqlite3() {
  if (sqlite3Promise) return sqlite3Promise

  importScripts(SQLITE_JS)

  if (globalThis.sqlite3InitModuleState) {
    globalThis.sqlite3InitModuleState.sqlite3Dir = SQLITE_DIR
    globalThis.sqlite3InitModuleState.scriptDir = SQLITE_DIR
  }

  sqlite3Promise = sqlite3InitModule({ printErr: console.error.bind(console) })
  return sqlite3Promise
}

async function openDatabase(fileName) {
  const sqlite = await sqlite3()
  const normalizedName = fileName.startsWith("/") ? fileName : `/${fileName}`
  const Db = await persistentDbFactory(sqlite)
  const db = new Db(normalizedName, "c")
  const databaseId = nextDatabaseId++

  databases.set(databaseId, db)
  return { databaseId }
}

async function persistentDbFactory(sqlite) {
  if (sqlite.oo1.OpfsDb) return sqlite.oo1.OpfsDb
  if (persistentDbFactoryPromise) return persistentDbFactoryPromise

  persistentDbFactoryPromise = sqlite.installOpfsSAHPoolVfs({
    name: "chat-drama-opfs",
    directory: ".chat-drama-opfs",
    initialCapacity: 16,
    clearOnInit: false,
    verbosity: 1,
  }).then(pool => pool.OpfsSAHPoolDb)

  return persistentDbFactoryPromise
}

function getDatabase(databaseId) {
  const db = databases.get(databaseId)
  if (!db) throw new Error(`Unknown database: ${databaseId}`)
  return db
}

function getStatement(statementId) {
  const entry = statements.get(statementId)
  if (!entry) throw new Error(`Unknown statement: ${statementId}`)
  return entry
}

function prepareStatement(databaseId, sql) {
  const stmt = getDatabase(databaseId).prepare(sql)
  const statementId = nextStatementId++

  statements.set(statementId, { databaseId, stmt })

  return {
    statementId,
    parameterCount: stmt.parameterCount,
    columnNames: stmt.columnCount > 0 ? stmt.getColumnNames([]) : [],
  }
}

function bindStatement(stmt, bindings = []) {
  stmt.clearBindings()

  bindings.forEach((value, index) => {
    if (value === undefined) return
    stmt.bind(index + 1, normalizeBinding(value))
  })
}

function normalizeBinding(value) {
  if (!ArrayBuffer.isView(value)) return value
  return new Uint8Array(value.buffer, value.byteOffset, value.byteLength)
}

async function stepStatement(statementId, bindings) {
  const sqlite = await sqlite3()
  const { stmt } = getStatement(statementId)

  bindStatement(stmt, bindings)

  const rows = []
  let columnTypes = []

  while (stmt.step()) {
    rows.push(stmt.get([]))
    if (columnTypes.length === 0) columnTypes = readColumnTypes(sqlite, stmt)
  }

  stmt.reset()
  return { rows, columnTypes }
}

function readColumnTypes(sqlite, stmt) {
  return Array.from({ length: stmt.columnCount }, (_, index) => sqlite.capi.sqlite3_column_type(stmt.pointer, index))
}

function closeStatement(statementId) {
  const entry = statements.get(statementId)
  if (!entry) return

  statements.delete(statementId)
  entry.stmt.finalize()
}

function closeDatabase(databaseId) {
  const db = databases.get(databaseId)
  if (!db) return

  for (const [statementId, entry] of statements) if (entry.databaseId === databaseId) closeStatement(statementId)

  databases.delete(databaseId)
  db.close()
}

self.onmessage = async (event) => {
  const { id, data } = event.data || {}

  try {
    switch (data?.cmd) {
      case "open": return reply(id, await openDatabase(data.fileName))

      case "prepare": return reply(id, prepareStatement(data.databaseId, data.sql))

      case "step": return reply(id, await stepStatement(data.statementId, data.bindings))

      case "close":
        if (data.statementId != null) closeStatement(data.statementId)
        if (data.databaseId != null) closeDatabase(data.databaseId)
        return

      default: throw new Error(`Unknown command: ${data?.cmd}`)
    }
  } catch (error) {
    replyError(id, error)
  }
}
