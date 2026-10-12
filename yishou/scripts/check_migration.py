"""Apply actual Kotlin migration SQL to Room snapshots, using SQLite. No Android SDK required."""
import json
import re
import sqlite3
from pathlib import Path

root = Path(__file__).resolve().parents[1]
schemas = root / 'app/schemas/com.yishou.app.data.AppDatabase'
source = (root / 'app/src/main/java/com/yishou/app/data/AppDatabase.kt').read_text()
migrations = {}
for start, end, body in re.findall(r'val MIGRATION_(\d+)_(\d+) =.*?override fun migrate\(db: SupportSQLiteDatabase\) \{(.*?)\n            \}', source, re.S):
    migrations[int(start), int(end)] = [
        ''.join(json.loads(token) for token in re.findall(r'"(?:[^"\\]|\\.)*"', expr))
        for expr in re.findall(r'db\.execSQL\(\s*((?:"(?:[^"\\]|\\.)*"\s*(?:\+\s*)?)+)\s*,?\s*\)', body, re.S)
    ]

def create(db, snapshot):
    for entity in snapshot['database']['entities']:
        name = entity['tableName']
        db.execute(entity['createSql'].replace('${TABLE_NAME}', name))
        for index in entity['indices']:
            db.execute(index['createSql'].replace('${TABLE_NAME}', name))

def structure(db):
    tables = db.execute("SELECT name FROM sqlite_master WHERE type='table' AND name != 'sqlite_sequence' ORDER BY name").fetchall()
    return {
        name: {
            'columns': list(db.execute(f'PRAGMA table_info("{name}")')),
            'indexes': [(row[1], row[2], list(db.execute(f'PRAGMA index_info("{row[1]}")'))) for row in db.execute(f'PRAGMA index_list("{name}")')],
            'foreign_keys': list(db.execute(f'PRAGMA foreign_key_list("{name}")')),
        }
        for (name,) in tables
    }

expected = sqlite3.connect(':memory:')
create(expected, json.loads((schemas / '6.json').read_text()))
for version in (3, 4, 5):
    db = sqlite3.connect(':memory:')
    create(db, json.loads((schemas / f'{version}.json').read_text()))
    db.execute("INSERT INTO task VALUES(1, '旧任务', '保留目标', '原材料', 1, 123)")
    db.execute("INSERT INTO breakpoint VALUES(1, '已知', '卡点', '下一问', '待回答的一手', 456, 2)")
    for previous in range(version, 6):
        for sql in migrations[previous, previous + 1]:
            db.execute(sql)
    assert structure(db) == structure(expected), f'v{version} does not match the v6 Room schema'
    assert db.execute('SELECT * FROM task').fetchone() == (1, '旧任务', '保留目标', '原材料', 1, 123, 'think')
    assert db.execute('SELECT * FROM breakpoint').fetchone() == (1, '已知', '卡点', '下一问', '待回答的一手', 456, 2)
    db.execute("UPDATE task SET practiceMode='action' WHERE id=1")
    assert db.execute('SELECT practiceMode FROM task').fetchone()[0] == 'action'
    print(f'v{version} → v6: schema and existing task/breakpoint data preserved')
