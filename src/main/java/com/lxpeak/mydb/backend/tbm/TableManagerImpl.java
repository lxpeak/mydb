package com.lxpeak.mydb.backend.tbm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import com.lxpeak.mydb.backend.dm.DataManager;
import com.lxpeak.mydb.backend.parser.statement.Create;
import com.lxpeak.mydb.backend.parser.statement.Delete;
import com.lxpeak.mydb.backend.parser.statement.Select;
import com.lxpeak.mydb.backend.vm.VersionManager;
import com.lxpeak.mydb.common.Error;
import com.lxpeak.mydb.backend.parser.statement.Begin;
import com.lxpeak.mydb.backend.parser.statement.Insert;
import com.lxpeak.mydb.backend.parser.statement.Update;
import com.lxpeak.mydb.backend.utils.Parser;

/*
* 由于 TBM 的表管理，使用的是链表串起的 Table 结构，所以就必须保存一个链表的头节点，
* 即第一个表的 UID，这样在 MYDB 启动时，才能快速找到表信息。
*
* MYDB 使用 Booter 类和 bt 文件，来管理 MYDB 的启动信息，虽然现在所需的启动信息，只有一个：头表的 UID。
* Booter 类对外提供了两个方法：load 和 update，并保证了其原子性。
* update 在修改 bt 文件内容时，没有直接对 bt 文件进行修改，而是首先将内容写入一个 bt_tmp 文件中，随后将这个文件重命名为 bt 文件。
* 以期通过操作系统重命名文件的原子性，来保证操作的原子性。
*
* */
//第九章
public class TableManagerImpl implements TableManager {
    VersionManager vm;
    DataManager dm;
    private Booter booter;
    private Map<String, Table> tableCache;
    private Map<Long, List<Table>> xidTableCache;
    private Lock lock;

    TableManagerImpl(VersionManager vm, DataManager dm, Booter booter) {
        this.vm = vm;
        this.dm = dm;
        this.booter = booter;
        this.tableCache = new HashMap<>();
        this.xidTableCache = new HashMap<>();
        lock = new ReentrantLock();
        loadTables();
    }

    private void loadTables() {
        long uid = firstTableUid();
        while(uid != 0) {
            Table tb = Table.loadTable(this, uid);
            uid = tb.nextUid;
            tableCache.put(tb.name, tb);
        }
    }

    private long firstTableUid() {
        byte[] raw = booter.load();
        return Parser.parseLong(raw);
    }

    private void updateFirstTableUid(long uid) {
        byte[] raw = Parser.long2Byte(uid);
        booter.update(raw);
    }

    @Override
    public BeginRes begin(Begin begin) {
        BeginRes res = new BeginRes();
        int level = begin.isRepeatableRead?1:0;
        res.xid = vm.begin(level);
        res.result = "begin".getBytes();
        return res;
    }
    @Override
    public byte[] commit(long xid) throws Exception {
        vm.commit(xid);
        return "commit".getBytes();
    }
    @Override
    public byte[] abort(long xid) {
        vm.abort(xid);
        return "abort".getBytes();
    }
    @Override
    public byte[] show(long xid) {
        lock.lock();
        try {
            StringBuilder sb = new StringBuilder();
            for (Table tb : tableCache.values()) {
                sb.append(tb.toString()).append("\n");
            }
            List<Table> t = xidTableCache.get(xid);
            if(t == null) {
                return "\n".getBytes();
            }
            for (Table tb : t) {
                sb.append(tb.toString()).append("\n");
            }
            return sb.toString().getBytes();
        } finally {
            lock.unlock();
        }
    }
    @Override
    public byte[] create(long xid, Create create) throws Exception {
        lock.lock();
        try {
            if(tableCache.containsKey(create.tableName)) {
                throw Error.DuplicatedTableException;
            }
            Table table = Table.createTable(this, firstTableUid(), xid, create);
            updateFirstTableUid(table.uid);
            tableCache.put(create.tableName, table);
            if(!xidTableCache.containsKey(xid)) {
                xidTableCache.put(xid, new ArrayList<>());
            }
            xidTableCache.get(xid).add(table);
            return ("create " + create.tableName).getBytes();
        } finally {
            lock.unlock();
        }
    }
    @Override
    public byte[] insert(long xid, Insert insert) throws Exception {
        lock.lock();
        Table table = tableCache.get(insert.tableName);
        lock.unlock();
        if(table == null) {
            throw Error.TableNotFoundException;
        }
        table.insert(xid, insert);
        return "insert".getBytes();
    }
    @Override
    public byte[] read(long xid, Select read) throws Exception {
        lock.lock();
        Table table = tableCache.get(read.tableName);
        lock.unlock();
        if(table == null) {
            throw Error.TableNotFoundException;
        }
        return table.read(xid, read).getBytes();
    }
    @Override
    public byte[] update(long xid, Update update) throws Exception {
        lock.lock();
        Table table = tableCache.get(update.tableName);
        lock.unlock();
        if(table == null) {
            throw Error.TableNotFoundException;
        }
        int count = table.update(xid, update);
        return ("update " + count).getBytes();
    }
    @Override
    //todo 别人问“为什么TBM的delete记录的时候，不需要删除索引呢”
    public byte[] delete(long xid, Delete delete) throws Exception {
        lock.lock();
        Table table = tableCache.get(delete.tableName);
        lock.unlock();
        if(table == null) {
            throw Error.TableNotFoundException;
        }
        int count = table.delete(xid, delete);
        return ("delete " + count).getBytes();
    }
}
