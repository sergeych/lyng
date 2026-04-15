#ifndef LYNG_SQLITE3_LYNG_H
#define LYNG_SQLITE3_LYNG_H

#ifdef __cplusplus
extern "C" {
#endif

typedef struct sqlite3 sqlite3;
typedef struct sqlite3_stmt sqlite3_stmt;
typedef long long sqlite3_int64;
typedef void (*sqlite3_destructor_type)(void*);

#define SQLITE_OK 0
#define SQLITE_ERROR 1
#define SQLITE_INTERNAL 2
#define SQLITE_PERM 3
#define SQLITE_ABORT 4
#define SQLITE_BUSY 5
#define SQLITE_LOCKED 6
#define SQLITE_NOMEM 7
#define SQLITE_READONLY 8
#define SQLITE_INTERRUPT 9
#define SQLITE_IOERR 10
#define SQLITE_CORRUPT 11
#define SQLITE_NOTFOUND 12
#define SQLITE_FULL 13
#define SQLITE_CANTOPEN 14
#define SQLITE_PROTOCOL 15
#define SQLITE_EMPTY 16
#define SQLITE_SCHEMA 17
#define SQLITE_TOOBIG 18
#define SQLITE_CONSTRAINT 19
#define SQLITE_MISMATCH 20
#define SQLITE_MISUSE 21
#define SQLITE_NOLFS 22
#define SQLITE_AUTH 23
#define SQLITE_FORMAT 24
#define SQLITE_RANGE 25
#define SQLITE_NOTADB 26
#define SQLITE_NOTICE 27
#define SQLITE_WARNING 28
#define SQLITE_ROW 100
#define SQLITE_DONE 101

#define SQLITE_INTEGER 1
#define SQLITE_FLOAT 2
#define SQLITE_TEXT 3
#define SQLITE_BLOB 4
#define SQLITE_NULL 5

#define SQLITE_OPEN_READONLY 0x00000001
#define SQLITE_OPEN_READWRITE 0x00000002
#define SQLITE_OPEN_CREATE 0x00000004
#define SQLITE_OPEN_URI 0x00000040

int sqlite3_open_v2(const char* filename, sqlite3** ppDb, int flags, const char* zVfs);
int sqlite3_close_v2(sqlite3*);
int sqlite3_extended_result_codes(sqlite3*, int onoff);
int sqlite3_busy_timeout(sqlite3*, int ms);
int sqlite3_prepare_v2(sqlite3* db, const char* zSql, int nByte, sqlite3_stmt** ppStmt, const char** pzTail);
int sqlite3_finalize(sqlite3_stmt* pStmt);
int sqlite3_step(sqlite3_stmt*);
int sqlite3_reset(sqlite3_stmt* pStmt);
int sqlite3_clear_bindings(sqlite3_stmt*);
int sqlite3_bind_parameter_count(sqlite3_stmt*);
int sqlite3_bind_null(sqlite3_stmt*, int);
int sqlite3_bind_int64(sqlite3_stmt*, int, sqlite3_int64);
int sqlite3_bind_double(sqlite3_stmt*, int, double);
int sqlite3_bind_text(sqlite3_stmt*, int, const char*, int n, sqlite3_destructor_type);
int sqlite3_bind_blob(sqlite3_stmt*, int, const void*, int n, sqlite3_destructor_type);
int sqlite3_column_count(sqlite3_stmt* pStmt);
const char* sqlite3_column_name(sqlite3_stmt*, int N);
const char* sqlite3_column_decltype(sqlite3_stmt*, int);
int sqlite3_column_type(sqlite3_stmt*, int iCol);
sqlite3_int64 sqlite3_column_int64(sqlite3_stmt*, int iCol);
double sqlite3_column_double(sqlite3_stmt*, int iCol);
const unsigned char* sqlite3_column_text(sqlite3_stmt*, int iCol);
const void* sqlite3_column_blob(sqlite3_stmt*, int iCol);
int sqlite3_column_bytes(sqlite3_stmt*, int iCol);
const char* sqlite3_errmsg(sqlite3*);
int sqlite3_extended_errcode(sqlite3*);
int sqlite3_changes(sqlite3*);
sqlite3_int64 sqlite3_last_insert_rowid(sqlite3*);
int sqlite3_db_readonly(sqlite3*, const char* zDbName);

static inline sqlite3* lyng_sqlite3_open(const char* filename, int flags) {
    sqlite3* db = 0;
    sqlite3_open_v2(filename, &db, flags, 0);
    return db;
}

static inline sqlite3_stmt* lyng_sqlite3_prepare(sqlite3* db, const char* sql) {
    sqlite3_stmt* stmt = 0;
    sqlite3_prepare_v2(db, sql, -1, &stmt, 0);
    return stmt;
}

#ifdef __cplusplus
}
#endif

#endif
