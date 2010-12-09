/* <GENERIC-HEADER - BEGIN>
 *
 * $(COMPANY) $(COPYRIGHT)
 *
 * Created on: Nov, 20, 2009
 * Created by: Thomas Hazel
 *
 * </GENERIC-HEADER - END> */

package com.akiban.ais.model;

import java.io.Serializable;
import java.util.*;

public class Index implements Serializable, ModelNames, Traversable
{
    public static Index create(AkibaInformationSchema ais, Map<String, Object> map)
    {
        Index index = null;
        String schemaName = (String) map.get(index_schemaName);
        String tableName = (String) map.get(index_tableName);
        String indexName = (String) map.get(index_indexName);
        Integer indexId = (Integer) map.get(index_indexId);
        Boolean unique = (Boolean) map.get(index_unique);
        String constraint = (String) map.get(index_constraint);
        Table table = ais.getTable(schemaName, tableName);
        if (table != null) {
            index = Index.create(ais, table, indexName, indexId, unique, constraint);
        }
        return index;
    }

    public static Index create(AkibaInformationSchema ais,
                               Table table,
                               String indexName,
                               Integer indexId,
                               Boolean isUnique,
                               String constraint)
    {
        Index index = new Index(table, indexName, indexId, isUnique, constraint);
        table.addIndex(index);
        return index;
    }
    
    // For a user table index: the user table hkey
    // For a group table index: the hkey of the leafmost user table, but with user table columns replaced by
    // group table columns.
    public HKey hKey()
    {
        if (hKey == null) {
            if (table.isUserTable()) {
                hKey = ((UserTable) table).hKey();
            } else {
                // Find the user table corresponding to this index. Currently, the columns of a group table index all
                // correspond to the same user table.
                UserTable userTable = null;
                for (IndexColumn indexColumn : columns) {
                    Column userColumn = indexColumn.getColumn().getUserColumn();
                    if (userTable == null) {
                        userTable = (UserTable) userColumn.getTable();
                    } else {
                        assert userTable == userColumn.getTable();
                    }
                }
                // Construct an hkey like userTable.hKey(), but with group columns replacing user columns.
                assert userTable != null : this;
                hKey = userTable.branchHKey();
            }
        }
        return hKey;
    }

    @SuppressWarnings("unused")
    private Index()
    {
        // GWT
    }

    public Index(Table table,
                 String indexName,
                 Integer indexId,
                 Boolean isUnique,
                 String constraint)
    {
        this.table = table;
        this.indexName = new IndexName(table, indexName);
        this.indexId = indexId;
        this.isUnique = isUnique;
        this.constraint = constraint;
        columns = new LinkedList<IndexColumn>();
    }

    @Override
    public String toString()
    {
        return "Index(" + indexName + ": " + table + ")";
    }

    public Map<String, Object> map()
    {
        Map<String, Object> map = new HashMap<String, Object>();
        map.put(index_schemaName, table.getName().getSchemaName());
        map.put(index_tableName, table.getName().getTableName());
        map.put(index_indexName, indexName.getName());
        map.put(index_indexId, indexId);
        map.put(index_unique, isUnique);
        map.put(index_constraint, constraint);
        return map;
    }

    public void addColumn(IndexColumn indexColumn)
    {
        columns.add(indexColumn);
        columnsStale = true;
    }

    public boolean isUnique()
    {
        return isUnique;
    }

    public boolean isPrimaryKey()
    {
        return constraint.equals("PRIMARY");
    }

    public Table getTable()
    {
        return table;
    }

    public String getConstraint()
    {
        return constraint;
    }

    public TableName getTableName()
    {
        return table.getName();
    }

    public IndexName getIndexName()
    {
        return indexName;
    }

    public List<IndexColumn> getColumns()
    {
        if (columnsStale) {
            Collections.sort(columns,
                             new Comparator<IndexColumn>()
                             {
                                 @Override
                                 public int compare(IndexColumn x, IndexColumn y)
                                 {
                                     return x.getPosition() - y.getPosition();
                                 }
                             });
            columnsStale = false;
        }
        return columns;
    }


    public Integer getIndexId()
    {
        return indexId;
    }

    public void setIndexId(Integer indexId)
    {
        this.indexId = indexId;
    }

    @Override
    public void traversePreOrder(Visitor visitor) throws Exception
    {
        for (IndexColumn indexColumn : getColumns()) {
            visitor.visitIndexColumn(indexColumn);
        }
    }

    @Override
    public void traversePostOrder(Visitor visitor) throws Exception
    {
        traversePreOrder(visitor);
    }

    private Table table;
    private IndexName indexName;
    private Integer indexId;
    private Boolean isUnique;
    private String constraint;
    private boolean columnsStale = true;
    private List<IndexColumn> columns;
    private transient HKey hKey;
}
