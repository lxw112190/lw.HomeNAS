"""Execute the actual queue schema/migration SQL on host SQLite; not Android/JVM tests."""
import json,re,sqlite3,unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
SOURCE=(ROOT/'android/app/src/main/java/com/lw/homenas/TaskStore.java').read_text(encoding='utf-8')
def strings(body):
    return [json.loads(match) for match in re.findall(r'db\.execSQL\(("(?:[^"\\]|\\.)*")\)',body)]
CREATE=SOURCE.split('public void onCreate(SQLiteDatabase db) {',1)[1].split('@Override',1)[0]
UPGRADE=SOURCE.split('public void onUpgrade(SQLiteDatabase db,int old,int next) {',1)[1].split('static final class Task',1)[0]
class QueueSqlCheck(unittest.TestCase):
    def test_real_upgrade_sql_keeps_acknowledged_offset_and_identity(self):
        with sqlite3.connect(':memory:') as db:
            fixture=(ROOT/'android/app/src/test/java/com/lw/homenas/TaskStoreTest.java').read_text(encoding='utf-8')
            old=json.loads(re.search(r'old\.execSQL\(("CREATE TABLE tasks.*?")\)',fixture).group(1));db.execute(old)
            db.execute('CREATE INDEX task_scope_state ON tasks(scope,state,id)');db.execute('CREATE INDEX task_scope_timeline ON tasks(scope,id DESC)')
            db.execute("INSERT INTO tasks(scope,source,uri,name,album,size,modified,client,sha,remote,offset,state) VALUES('scope','source','content://one','one.mp4','Camera',6000000000,1,'same-client','same-sha','same-remote',4294967296,'UPLOADING')")
            for token in re.finditer(r'db\.execSQL\(("(?:[^"\\]|\\.)*")\)|onCreate\(db\)',UPGRADE):
                if token.group(1):db.execute(json.loads(token.group(1)))
                else:
                    for statement in strings(CREATE):db.execute(statement)
            self.assertEqual(db.execute('SELECT client,sha,remote,offset,state,folder_id FROM tasks').fetchone(),('same-client','same-sha','same-remote',4294967296,'UPLOADING','default'))
            self.assertFalse(db.execute("SELECT name FROM sqlite_master WHERE name='tasks_v1'").fetchall())
    def test_destinations_are_part_of_queue_uniqueness(self):
        with sqlite3.connect(':memory:') as db:
            for statement in strings(CREATE):db.execute(statement)
            insert="INSERT INTO tasks(scope,source,uri,name,album,size,modified,client,folder_id) VALUES('scope','same','content://one','one','Camera',1,1,'client',?)"
            db.execute(insert,('default',));db.execute(insert,('a'*48,))
            with self.assertRaises(sqlite3.IntegrityError):db.execute(insert,('a'*48,))
            self.assertEqual(db.execute('SELECT count(*) FROM tasks').fetchone()[0],2)
if __name__=='__main__':unittest.main()
