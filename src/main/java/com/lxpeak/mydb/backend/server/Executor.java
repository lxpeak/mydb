package com.lxpeak.mydb.backend.server;

import com.lxpeak.mydb.backend.parser.Parser;
import com.lxpeak.mydb.backend.tbm.BeginRes;
import com.lxpeak.mydb.common.Error;
import com.lxpeak.mydb.backend.parser.statement.Abort;
import com.lxpeak.mydb.backend.parser.statement.Begin;
import com.lxpeak.mydb.backend.parser.statement.Commit;
import com.lxpeak.mydb.backend.parser.statement.Create;
import com.lxpeak.mydb.backend.parser.statement.Delete;
import com.lxpeak.mydb.backend.parser.statement.Insert;
import com.lxpeak.mydb.backend.parser.statement.Select;
import com.lxpeak.mydb.backend.parser.statement.Show;
import com.lxpeak.mydb.backend.parser.statement.Update;
import com.lxpeak.mydb.backend.tbm.TableManager;

// 不是线程池父类
public class Executor {
    private long xid;
    TableManager tbm;

    public Executor(TableManager tbm) {
        this.tbm = tbm;
        this.xid = 0;
    }

    public void close() {
        if(xid != 0) {
            System.out.println("Abnormal Abort: " + xid);
            tbm.abort(xid);
        }
    }

    public byte[] execute(byte[] sql) throws Exception {
        System.out.println("Execute: " + new String(sql));
        // 根据Parse()方法得到sql语句对应的结构化信息对象（Begin、Commit、Abort、Show、Create、Select、Insert、Delete、Update），
        // 然后根据对象的类型调用 TBM 的不同方法进行处理。
        Object stat = Parser.Parse(sql);
        if(Begin.class.isInstance(stat)) {
            if(xid != 0) {
                throw Error.NestedTransactionException;
            }
            BeginRes r = tbm.begin((Begin)stat);
            xid = r.xid;
            return r.result;
        } else if(Commit.class.isInstance(stat)) {
            if(xid == 0) {
                throw Error.NoTransactionException;
            }
            byte[] res = tbm.commit(xid);
            xid = 0;
            return res;
        } else if(Abort.class.isInstance(stat)) {
            if(xid == 0) {
                throw Error.NoTransactionException;
            }
            byte[] res = tbm.abort(xid);
            xid = 0;
            return res;
        } else {
            return execute2(stat);
        }
    }

    private byte[] execute2(Object stat) throws Exception {
        boolean tmpTransaction = false;
        Exception e = null;
        if(xid == 0) {
            tmpTransaction = true;
            BeginRes r = tbm.begin(new Begin());
            xid = r.xid;
        }
        try {
            byte[] res = null;
            if(Show.class.isInstance(stat)) {
                res = tbm.show(xid);
            } else if(Create.class.isInstance(stat)) {
                res = tbm.create(xid, (Create)stat);
            } else if(Select.class.isInstance(stat)) {
                res = tbm.read(xid, (Select)stat);
            } else if(Insert.class.isInstance(stat)) {
                res = tbm.insert(xid, (Insert)stat);
            } else if(Delete.class.isInstance(stat)) {
                res = tbm.delete(xid, (Delete)stat);
            } else if(Update.class.isInstance(stat)) {
                res = tbm.update(xid, (Update)stat);
            }
            return res;
        } catch(Exception e1) {
            e = e1;
            throw e;
        } finally {
            if(tmpTransaction) {
                if(e != null) {
                    tbm.abort(xid);
                } else {
                    tbm.commit(xid);
                }
                xid = 0;
            }
        }
    }
}
