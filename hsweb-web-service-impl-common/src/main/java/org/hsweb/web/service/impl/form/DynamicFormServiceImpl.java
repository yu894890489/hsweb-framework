package org.hsweb.web.service.impl.form;

import com.alibaba.fastjson.JSON;
import org.hsweb.concurrent.lock.annotation.LockName;
import org.hsweb.concurrent.lock.annotation.ReadLock;
import org.hsweb.concurrent.lock.annotation.WriteLock;
import org.hsweb.web.bean.common.*;
import org.hsweb.web.bean.po.GenericPo;
import org.hsweb.web.bean.po.form.Form;
import org.hsweb.web.bean.po.history.History;
import org.hsweb.web.core.Install;
import org.hsweb.web.service.form.DynamicFormService;
import org.hsweb.web.service.form.FormService;
import org.hsweb.web.service.history.HistoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.webbuilder.sql.*;
import org.webbuilder.sql.exception.CreateException;
import org.webbuilder.sql.param.ExecuteCondition;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Created by zhouhao on 16-4-14.
 */
@Service("dynamicFormService")
public class DynamicFormServiceImpl implements DynamicFormService {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    @Autowired(required = false)
    protected FormParser formParser = new DefaultFormParser();

    @Autowired
    protected DataBase dataBase;

    @Resource
    protected FormService formService;

    @Resource
    protected HistoryService historyService;

    protected void initDefaultField(TableMetaData metaData) {
        String dataType;
        switch (Install.getDatabaseType()) {
            case "oracle":
                dataType = "varchar2(32)";
                break;
            case "h2":
                dataType = "varchar2(32)";
                break;
            default:
                dataType = "varchar(32)";
        }
        FieldMetaData UID = new FieldMetaData("u_id", String.class, dataType);
        UID.setPrimaryKey(true);
        UID.setNotNull(true);
        UID.setComment("主键");
        metaData.attr("primaryKey", "u_id");
        metaData.addField(UID);

    }

    @PostConstruct
    public void init() {
        QueryParam param = new QueryParam();
        param.where("using", 1);
        try {
            formService.select(param).forEach(form -> {
                try {
                    deploy(form);
                } catch (Exception e) {
                    logger.error("", e);
                }
            });
        } catch (Exception e) {
            logger.error("", e);
        }
    }

    @Override
    public Object parseMeta(Form form) throws Exception {
        return formParser.parse(form);
    }

    @Override
    @WriteLock
    @LockName(value = "'form.lock.'+#form.name", isExpression = true)
    public void deploy(Form form) throws Exception {
        TableMetaData metaData = formParser.parse(form);
        initDefaultField(metaData);
        History history = historyService.selectLastHistoryByType("form.deploy." + form.getName());
        //首次部署
        if (history == null) {
            try {
                dataBase.createTable(metaData);
            } catch (CreateException e) {
                dataBase.updateTable(metaData);
            }
        } else {
            Form lastDeploy = JSON.parseObject(history.getChangeAfter(), Form.class);
            TableMetaData lastDeployMetaData = formParser.parse(lastDeploy);
            initDefaultField(lastDeployMetaData);
            //向上发布
            dataBase.updateTable(lastDeployMetaData);//先放入旧的结构
            //更新结构
            dataBase.alterTable(metaData);
        }
    }

    @Override
    @WriteLock
    @LockName(value = "'form.lock.'+#form.name", isExpression = true)
    public void unDeploy(Form form) throws Exception {
        dataBase.removeTable(form.getName());
    }

    public Table getTableByName(String name) throws Exception {
        Table table = dataBase.getTable(name.toUpperCase());
        if (table == null)
            table = dataBase.getTable(name.toLowerCase());
        Assert.notNull(table, "表单[" + name + "]不存在");
        return table;
    }

    @Override
    @ReadLock
    @LockName(value = "'form.lock.'+#name", isExpression = true)
    public <T> PagerResult<T> selectPager(String name, QueryParam param) throws Exception {
        PagerResult<T> result = new PagerResult<>();
        Table table = getTableByName(name);
        Query query = table.createQuery();
        QueryParamProxy proxy = QueryParamProxy.build(param);
        int total = query.total(proxy);
        result.setTotal(total);
        param.rePaging(total);
        proxy = QueryParamProxy.build(param);
        result.setData(query.list(proxy));
        return result;
    }

    @Override
    @ReadLock
    @LockName(value = "'form.lock.'+#name", isExpression = true)
    public <T> List<T> select(String name, QueryParam param) throws Exception {
        Table table = getTableByName(name);
        Query query = table.createQuery();
        param.setPaging(false);
        QueryParamProxy proxy = QueryParamProxy.build(param);
        return query.list(proxy);
    }

    @Override
    @ReadLock
    @LockName(value = "'form.lock.'+#name", isExpression = true)
    public int total(String name, QueryParam param) throws Exception {
        Table table = getTableByName(name);
        Query query = table.createQuery();
        param.setPaging(false);
        QueryParamProxy proxy = QueryParamProxy.build(param);
        return query.total(proxy);
    }

    @Override
    @ReadLock
    @LockName(value = "'form.lock.'+#name", isExpression = true)
    public String insert(String name, InsertParam<Map<String, Object>> param) throws Exception {
        Table table = getTableByName(name);
        Insert insert = table.createInsert();
        InsertParamProxy paramProxy = InsertParamProxy.build(param);
        String primaryKeyName = getPrimaryKeyName(name);
        String pk = GenericPo.createUID();
        paramProxy.value(primaryKeyName, pk);
        insert.insert(paramProxy);
        return pk;
    }

    @Override
    @ReadLock
    @LockName(value = "'form.lock.'+#name", isExpression = true)
    public boolean deleteByPk(String name, String pk) throws Exception {
        String primaryKeyName = getPrimaryKeyName(name);
        Table table = getTableByName(name);
        Delete delete = table.createDelete();
        return delete.delete(DeleteParamProxy.build(new DeleteParam()).where(primaryKeyName, pk)) == 1;
    }

    @Override
    @ReadLock
    @LockName(value = "'form.lock.'+#name", isExpression = true)
    public int delete(String name, DeleteParam where) throws Exception {
        Table table = getTableByName(name);
        Delete delete = table.createDelete();
        return delete.delete(DeleteParamProxy.build(where));
    }

    @Override
    @ReadLock
    @LockName(value = "'form.lock.'+#name", isExpression = true)
    public int updateByPk(String name, String pk, UpdateParam<Map<String, Object>> param) throws Exception {
        Table table = getTableByName(name);
        Update update = table.createUpdate();
        UpdateParamProxy paramProxy = UpdateParamProxy.build(param);
        paramProxy.where(getPrimaryKeyName(name), pk);
        return update.update(paramProxy);
    }

    @Override
    @ReadLock
    @LockName(value = "'form.lock.'+#name", isExpression = true)
    public int update(String name, UpdateParam<Map<String, Object>> param) throws Exception {
        Table table = getTableByName(name);
        Update update = table.createUpdate();
        UpdateParamProxy paramProxy = UpdateParamProxy.build(param);
        return update.update(paramProxy);
    }

    @ReadLock
    @LockName(value = "'form.lock.'+#tableName", isExpression = true)
    public String getPrimaryKeyName(String tableName) throws Exception {
        Table table = getTableByName(tableName);
        return table.getMetaData().attrWrapper("primaryKey", "u_id").toString();
    }

    @Override
    @ReadLock
    @LockName(value = "'form.lock.'+#name", isExpression = true)
    public <T> T selectByPk(String name, Object pk) throws Exception {
        Table table = getTableByName(name);
        Query query = table.createQuery();
        QueryParamProxy proxy = new QueryParamProxy();
        proxy.where(getPrimaryKeyName(name), pk);
        return query.single(proxy);
    }

    public static class QueryParamProxy extends org.webbuilder.sql.param.query.QueryParam {
        public QueryParamProxy orderBy(String mode, Set<String> fields) {
            addProperty("order_by", fields);
            addProperty("order_by_mod", mode);
            return this;
        }

        public static QueryParamProxy build(QueryParam param) {
            QueryParamProxy proxy = new QueryParamProxy();
            proxy.setConditions(term2cdt(param.getTerms()));
            proxy.exclude(param.getExcludes());
            proxy.include(param.getIncludes());
            proxy.orderBy(param.getSortOrder(), param.getSortField());
            proxy.doPaging(param.getPageIndex(), param.getPageSize());
            proxy.setPaging(param.isPaging());
            return proxy;
        }
    }

    public static class UpdateParamProxy extends org.webbuilder.sql.param.update.UpdateParam {
        public static UpdateParamProxy build(UpdateParam<Map<String, Object>> param) {
            UpdateParamProxy proxy = new UpdateParamProxy();
            proxy.setConditions(term2cdt(param.getTerms()));
            proxy.exclude(param.getExcludes());
            proxy.include(param.getIncludes());
            proxy.set(param.getData());
            return proxy;
        }
    }

    public static class InsertParamProxy extends org.webbuilder.sql.param.insert.InsertParam {
        public static InsertParamProxy build(InsertParam<Map<String, Object>> param) {
            InsertParamProxy proxy = new InsertParamProxy();
            proxy.values(param.getData());
            return proxy;
        }
    }

    public static class DeleteParamProxy extends org.webbuilder.sql.param.delete.DeleteParam {
        public static DeleteParamProxy build(DeleteParam param) {
            DeleteParamProxy proxy = new DeleteParamProxy();
            proxy.setConditions(term2cdt(param.getTerms()));
            return proxy;
        }
    }

    protected static Set<ExecuteCondition> term2cdt(List<Term> terms) {
        Set<ExecuteCondition> set = new LinkedHashSet<>();
        terms.forEach(term -> {
            ExecuteCondition executeCondition = new ExecuteCondition();
            executeCondition.setAppendType(term.getType().toString());
            executeCondition.setField(term.getField());
            executeCondition.setValue(term.getValue());
            executeCondition.setQueryType(term.getTermType().toString().toUpperCase());
            executeCondition.setSql(false);
            if (!term.getTerms().isEmpty())
                executeCondition.setNest(term2cdt(term.getTerms()));
            set.add(executeCondition);
        });
        return set;
    }
}
