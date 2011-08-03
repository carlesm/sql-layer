/**
 * Copyright (C) 2011 Akiban Technologies Inc.
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses.
 */

package com.akiban.server.service.dxl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.akiban.ais.model.AkibanInformationSchema;
import com.akiban.ais.model.Group;
import com.akiban.ais.model.Index;
import com.akiban.ais.model.Join;
import com.akiban.ais.model.Table;
import com.akiban.ais.model.TableName;
import com.akiban.ais.model.UserTable;
import com.akiban.server.RowDef;
import com.akiban.server.api.DDLFunctions;
import com.akiban.server.api.DMLFunctions;
import com.akiban.server.api.GenericInvalidOperationException;
import com.akiban.server.api.ddl.GroupWithProtectedTableException;
import com.akiban.server.api.ddl.IndexAlterException;
import com.akiban.server.api.dml.scan.Cursor;
import com.akiban.server.api.dml.scan.CursorId;
import com.akiban.server.api.dml.scan.ScanRequest;
import com.akiban.server.error.DropIndexNotAllowedException;
import com.akiban.server.error.DuplicateColumnNameException;
import com.akiban.server.error.DuplicateKeyException;
import com.akiban.server.error.DuplicateTableNameException;
import com.akiban.server.error.ErrorCode;
import com.akiban.server.error.ForeignConstraintDDLException;
import com.akiban.server.error.InvalidOperationException;
import com.akiban.server.error.JoinToMultipleParentsException;
import com.akiban.server.error.JoinToUnknownTableException;
import com.akiban.server.error.JoinToWrongColumnsException;
import com.akiban.server.error.NoSuchGroupException;
import com.akiban.server.error.NoSuchIndexException;
import com.akiban.server.error.NoSuchTableException;
import com.akiban.server.error.NoSuchTableIdException;
import com.akiban.server.error.ParseException;
import com.akiban.server.error.ProtectedTableDDLException;
import com.akiban.server.error.RowDefNotFoundException;
import com.akiban.server.error.UnknownIndexException;
import com.akiban.server.error.UnsupportedCharsetException;
import com.akiban.server.error.UnsupportedDataTypeException;
import com.akiban.server.error.UnsupportedDropException;
import com.akiban.server.error.UnsupportedIndexDataTypeException;
import com.akiban.server.error.UnsupportedIndexSizeException;
import com.akiban.server.service.session.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.akiban.util.Exceptions.throwIfInstanceOf;

class BasicDDLFunctions extends ClientAPIBase implements DDLFunctions {

    private final static Logger logger = LoggerFactory.getLogger(BasicDDLFunctions.class);

    @Override
    public void createTable(Session session, String schema, String ddlText)
            throws ParseException, 
            GroupWithProtectedTableException, 
            GenericInvalidOperationException
    {
        logger.trace("creating table: ({}) {}", schema, ddlText);
        try {
            TableName tableName = schemaManager().createTableDefinition(session, schema, ddlText);
            checkCursorsForDDLModification(session, getAIS(session).getTable(tableName));
        } catch (Exception e) {
            InvalidOperationException ioe = launder(e);
            throwIfInstanceOf(ioe,
                    ParseException.class
            );
            throw new GenericInvalidOperationException(ioe);
        }
    }
    
    @Override
    public void createTable(Session session, UserTable table)
    throws ParseException, GroupWithProtectedTableException, 
    GenericInvalidOperationException
    {
        try {
            TableName tableName = schemaManager().createTableDefinition(session, table);
            checkCursorsForDDLModification(session, getAIS(session).getTable(tableName));
        } catch (Exception e) {
            InvalidOperationException ioe = launder(e);
            throwIfInstanceOf(ioe,
                    ParseException.class
            );
            throw new GenericInvalidOperationException(ioe);
        }
    }

    @Override
    public void renameTable(Session session, TableName currentName, TableName newName)
            throws GenericInvalidOperationException
    {
        try {
            schemaManager().renameTable(session, currentName, newName);
            checkCursorsForDDLModification(session, getAIS(session).getTable(newName));
        } catch (Exception e) {
            InvalidOperationException ioe = launder(e);
            throw new GenericInvalidOperationException(ioe);
        }
    }


    @Override
    public void dropTable(Session session, TableName tableName)
            throws GenericInvalidOperationException
    {
        logger.trace("dropping table {}", tableName);
        final Table table = getAIS(session).getTable(tableName);
        
        if(table == null) {
            return; // dropping a non-existing table is a no-op
        }

        final UserTable userTable = table.isUserTable() ? (UserTable)table : null;

        // Halo spec: may only drop leaf tables through DDL interface
        if(userTable == null || userTable.getChildJoins().isEmpty() == false) {
            throw new UnsupportedDropException(table.getName());
        }

        try {
            DMLFunctions dml = new BasicDMLFunctions(middleman(), this);
            if(userTable.getParentJoin() == null) {
                // Root table and no child tables, can delete all associated trees
                store().removeTrees(session, table);
            }
            else {
                dml.truncateTable(session, table.getTableId());
                store().deleteIndexes(session, userTable.getIndexesIncludingInternal());
                store().deleteIndexes(session, userTable.getGroupIndexes());
            }
            schemaManager().deleteTableDefinition(session, tableName.getSchemaName(), tableName.getTableName());
            checkCursorsForDDLModification(session, table);
        } catch (Exception e) {
            throw new GenericInvalidOperationException(e);
        }
    }

    @Override
    public void dropSchema(Session session, String schemaName)
            throws GenericInvalidOperationException
    {
        logger.trace("dropping schema {}", schemaName);

        // Find all groups and tables in the schema
        Set<Group> groupsToDrop = new HashSet<Group>();
        List<UserTable> tablesToDrop = new ArrayList<UserTable>();

        final AkibanInformationSchema ais = getAIS(session);
        for(UserTable table : ais.getUserTables().values()) {
            final TableName tableName = table.getName();
            if(tableName.getSchemaName().equals(schemaName)) {
                groupsToDrop.add(table.getGroup());
                // Cannot drop entire group of parent is not in the same schema
                final Join parentJoin = table.getParentJoin();
                if(parentJoin != null) {
                    final UserTable parentTable = parentJoin.getParent();
                    if(!parentTable.getName().getSchemaName().equals(schemaName)) {
                        tablesToDrop.add(table);
                    }
                }
                // All children must be in the same schema
                for(Join childJoin : table.getChildJoins()) {
                    final TableName childName = childJoin.getChild().getName();
                    if(!childName.getSchemaName().equals(schemaName)) {
                        throw new ForeignConstraintDDLException(tableName, childName);
                    }
                }
            }
        }
        // Remove groups that contain tables in multiple schemas
        for(UserTable table : tablesToDrop) {
            groupsToDrop.remove(table.getGroup());
        }
        // Sort table IDs so higher (i.e. children) are first
        Collections.sort(tablesToDrop, new Comparator<UserTable>() {
            @Override
            public int compare(UserTable o1, UserTable o2) {

                return o2.getTableId().compareTo(o1.getTableId());
            }
        });
        // Do the actual dropping
        for(UserTable table : tablesToDrop) {
            dropTable(session, table.getName());
        }
        for(Group group : groupsToDrop) {
            dropGroup(session, group.getName());
        }
    }

    @Override
    public void dropGroup(Session session, String groupName)
            throws GenericInvalidOperationException
    {
        logger.trace("dropping group {}", groupName);
        final Group group = getAIS(session).getGroup(groupName);
        if(group == null) {
            return;
        }
        try {
            final Table table = group.getGroupTable();
            final RowDef rowDef = getRowDef(table.getTableId());
            final TableName tableName = table.getName();
            store().truncateGroup(session, rowDef.getRowDefId());
            schemaManager().deleteTableDefinition(session, tableName.getSchemaName(), tableName.getTableName());
            checkCursorsForDDLModification(session, table);
        } catch(Exception e) {
            throw new GenericInvalidOperationException(e);
        }
    }

    @Override
    public AkibanInformationSchema getAIS(final Session session) {
        logger.trace("getting AIS");
        return schemaManager().getAis(session);
    }

    @Override
    public int getTableId(Session session, TableName tableName) {
        logger.trace("getting table ID for {}", tableName);
        Table table = getAIS(session).getTable(tableName);
        if (table == null) {
            throw new NoSuchTableException(tableName);
        }
        return table.getTableId();
    }

    @Override
    public Table getTable(Session session, int tableId) {
        logger.trace("getting AIS Table for {}", tableId);
        for (Table userTable : getAIS(session).getUserTables().values()) {
            if (tableId == userTable.getTableId()) {
                return userTable;
            }
        }
        for (Table groupTable : getAIS(session).getGroupTables().values()) {
            if (tableId == groupTable.getTableId()) {
                return groupTable;
            }
        }
        throw new NoSuchTableIdException(tableId);
    }

    @Override
    public Table getTable(Session session, TableName tableName) {
        logger.trace("getting AIS Table for {}", tableName);
        AkibanInformationSchema ais = getAIS(session);
        Table table = ais.getTable(tableName);
        if (table == null) {
            throw new NoSuchTableException(tableName);
        }
        return table;
    }

    @Override
    public UserTable getUserTable(Session session, TableName tableName) {
        logger.trace("getting AIS UserTable for {}", tableName);
        AkibanInformationSchema ais = getAIS(session);
        UserTable table = ais.getUserTable(tableName);
        if (table == null) {
            throw new NoSuchTableException(tableName);
        }
        return table;
    }

    @Override
    public TableName getTableName(Session session, int tableId) {
        logger.trace("getting table name for {}", tableId);
        return getTable(session, tableId).getName();
    }

    @Override
    public RowDef getRowDef(int tableId) {
        logger.trace("getting RowDef for {}", tableId);
        return store().getRowDefCache().getRowDef(tableId);
    }

    @Override
    public List<String> getDDLs(final Session session) throws InvalidOperationException {
        logger.trace("getting DDLs");
        try {
            return schemaManager().schemaStrings(session, false);
        } catch (Exception e) {
            throw new InvalidOperationException(ErrorCode.UNEXPECTED_EXCEPTION,
                    "Unexpected exception", e);
        }
    }

    @Override
    public int getGeneration() {
        return schemaManager().getSchemaGeneration();
    }

    @Override
    @SuppressWarnings("unused")
    // meant to be used from JMX
    public void forceGenerationUpdate() {
        logger.trace("forcing schema generation update");
        schemaManager().forceNewTimestamp();
    }

    @Override
    public void createIndexes(final Session session, Collection<Index> indexesToAdd)
            throws GenericInvalidOperationException {
        logger.trace("creating indexes {}", indexesToAdd);
        if (indexesToAdd.isEmpty() == true) {
            return;
        }

        final Collection<Index> newIndexes;
        try {
            newIndexes = schemaManager().createIndexes(session, indexesToAdd);
        }
        catch(InvalidOperationException e) {
            throw e;
            //throwIfInstanceOf(e, NoSuchTableException.class, NoSuchGroupException.class);
            //throw new IndexAlterException(e);
        }
        catch(Exception e) {
            throw new GenericInvalidOperationException(e);
        }

        for(Index index : newIndexes) {
            checkCursorsForDDLModification(session, index.leafMostTable());
        }

        try {
            store().buildIndexes(session, newIndexes, false);
        } catch(Exception e) {
            // Try and roll back all changes
            try {
                store().deleteIndexes(session, newIndexes);
                schemaManager().dropIndexes(session, newIndexes);
            } catch(Exception e2) {
                logger.error("Exception while rolling back failed createIndex: " + newIndexes, e2);
            }
            throw e;
        }
    }

    @Override
    public void dropTableIndexes(final Session session, TableName tableName, Collection<String> indexNamesToDrop)
            throws GenericInvalidOperationException
    {
        logger.trace("dropping table indexes {} {}", tableName, indexNamesToDrop);
        if(indexNamesToDrop.isEmpty() == true) {
            return;
        }

        final Table table = getTable(session, tableName);
        Collection<Index> indexes = new HashSet<Index>();
        for(String indexName : indexNamesToDrop) {
            Index index = table.getIndex(indexName);
            if(index == null) {
                throw new NoSuchIndexException (indexName);
            }
            if(index.isPrimaryKey()) {
                throw new DropIndexNotAllowedException ("PRIMARY", tableName);
            }
            indexes.add(index);
        }
        
        try {
            // Drop them from the Store before while IndexDefs still exist
            store().deleteIndexes(session, indexes);
            schemaManager().dropIndexes(session, indexes);
            checkCursorsForDDLModification(session, table);
        } catch(Exception e) {
            throw new GenericInvalidOperationException(e);
        }
    }

    @Override
    public void dropGroupIndexes(Session session, String groupName, Collection<String> indexNamesToDrop)
            throws GenericInvalidOperationException {
        logger.trace("dropping group indexes {} {}", groupName, indexNamesToDrop);
        if(indexNamesToDrop.isEmpty()) {
            return;
        }

        final Group group = getAIS(session).getGroup(groupName);
        if (group == null) {
            throw new NoSuchGroupException(groupName);
        }

        Collection<Index> indexes = new HashSet<Index>();
        for(String indexName : indexNamesToDrop) {
            final Index index = group.getIndex(indexName);
            if(index == null) {
                throw new NoSuchIndexException(indexName);
            }
            indexes.add(index);
        }

        try {
            // Drop them from the Store before while IndexDefs still exist
            store().deleteIndexes(session, indexes);
            schemaManager().dropIndexes(session, indexes);
            // TODO: checkCursorsForDDLModification ?
        } catch(Exception e) {
            throw new GenericInvalidOperationException(e);
        }
    }

    private void checkCursorsForDDLModification(Session session, Table table) {
        Map<CursorId,BasicDXLMiddleman.ScanData> cursorsMap = getScanDataMap(session);
        if (cursorsMap == null) {
            return;
        }

        final int tableId;
        final int gTableId;
        {
            if (table.isUserTable()) {
                tableId = table.getTableId();
                gTableId = table.getGroup().getGroupTable().getTableId();
            }
            else {
                tableId = gTableId = table.getTableId();
            }
        }

        for (BasicDXLMiddleman.ScanData scanData : cursorsMap.values()) {
            Cursor cursor = scanData.getCursor();
            if (cursor.isClosed()) {
                continue;
            }
            ScanRequest request = cursor.getScanRequest();
            int scanTableId = request.getTableId();
            if (scanTableId == tableId || scanTableId == gTableId) {
                cursor.setDDLModified();
            }
        }
    }

    BasicDDLFunctions(BasicDXLMiddleman middleman) {
        super(middleman);
    }
}
