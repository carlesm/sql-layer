/**
 * END USER LICENSE AGREEMENT (“EULA”)
 *
 * READ THIS AGREEMENT CAREFULLY (date: 9/13/2011):
 * http://www.akiban.com/licensing/20110913
 *
 * BY INSTALLING OR USING ALL OR ANY PORTION OF THE SOFTWARE, YOU ARE ACCEPTING
 * ALL OF THE TERMS AND CONDITIONS OF THIS AGREEMENT. YOU AGREE THAT THIS
 * AGREEMENT IS ENFORCEABLE LIKE ANY WRITTEN AGREEMENT SIGNED BY YOU.
 *
 * IF YOU HAVE PAID A LICENSE FEE FOR USE OF THE SOFTWARE AND DO NOT AGREE TO
 * THESE TERMS, YOU MAY RETURN THE SOFTWARE FOR A FULL REFUND PROVIDED YOU (A) DO
 * NOT USE THE SOFTWARE AND (B) RETURN THE SOFTWARE WITHIN THIRTY (30) DAYS OF
 * YOUR INITIAL PURCHASE.
 *
 * IF YOU WISH TO USE THE SOFTWARE AS AN EMPLOYEE, CONTRACTOR, OR AGENT OF A
 * CORPORATION, PARTNERSHIP OR SIMILAR ENTITY, THEN YOU MUST BE AUTHORIZED TO SIGN
 * FOR AND BIND THE ENTITY IN ORDER TO ACCEPT THE TERMS OF THIS AGREEMENT. THE
 * LICENSES GRANTED UNDER THIS AGREEMENT ARE EXPRESSLY CONDITIONED UPON ACCEPTANCE
 * BY SUCH AUTHORIZED PERSONNEL.
 *
 * IF YOU HAVE ENTERED INTO A SEPARATE WRITTEN LICENSE AGREEMENT WITH AKIBAN FOR
 * USE OF THE SOFTWARE, THE TERMS AND CONDITIONS OF SUCH OTHER AGREEMENT SHALL
 * PREVAIL OVER ANY CONFLICTING TERMS OR CONDITIONS IN THIS AGREEMENT.
 */

package com.akiban.ais.model;

import com.akiban.ais.AISCloner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.akiban.ais.model.validation.AISValidations;
import com.akiban.server.error.JoinToMultipleParentsException;
import com.akiban.server.error.JoinToUnknownTableException;
import com.akiban.server.error.JoinToWrongColumnsException;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * AISMerge is designed to merge a single UserTable definition into an existing AIS. The merge process 
 * does not assume that UserTable.getAIS() returns a validated and complete 
 * AkibanInformationSchema object. 
 * 
 * AISMerge makes a copy of the primaryAIS (from the constructor) before performing the merge process. 
 * The final results is this copies AIS, plus new table, with the full AISValidations suite run, and 
 * frozen. If you pass a frozen AIS into the merge, the copy process unfreeze the copy.
 */
public class AISMerge {
    // Use 1 as default offset because the AAM uses tableID 0 as a marker value.
    static final int USER_TABLE_ID_OFFSET = 1;
    static final int AIS_TABLE_ID_OFFSET = 1000000000;
    private static final Logger LOG = LoggerFactory.getLogger(AISMerge.class);

    /* state */
    private final AkibanInformationSchema targetAIS;
    private final UserTable sourceTable;
    private final NameGenerator nameGenerator;
    private SortedSet<Integer> userTableIDSet = new TreeSet<Integer>();
    private SortedSet<Integer> isTableIDSet = new TreeSet<Integer>();

    /**
     * Creates an AISMerger with the starting values. 
     * 
     * @param primaryAIS - where the table will end up
     * @param newTable - UserTable to merge into the primaryAIS
     */
    public AISMerge (AkibanInformationSchema primaryAIS, UserTable newTable) {
        targetAIS = copyAIS(primaryAIS);
        sourceTable = newTable;
        nameGenerator = new DefaultNameGenerator().
                setDefaultGroupNames(targetAIS.getGroups().keySet()).
                setDefaultSequenceNames(computeSequenceNames(targetAIS)).
                setDefaultTreeNames(computeTreeNames(targetAIS));
        collectTableIDs(primaryAIS);
    }
    
    public static AkibanInformationSchema copyAIS(AkibanInformationSchema oldAIS) {
        return AISCloner.clone(oldAIS);
    }

    /**
     * Returns the final, updated AkibanInformationSchema. This AIS has been fully 
     * validated and is frozen (no more changes), hence ready for update into the
     * server. 
     * @return - the primaryAIS, after merge() with the UserTable added.
     */
    public AkibanInformationSchema getAIS () {
        return targetAIS;
    }
    
    public AISMerge merge() {
        // I should use TableSubsetWriter(new AISTarget(targetAIS))
        // but that assumes the UserTable.getAIS() is complete and valid. 
        // i.e. has a group and group table, joins are accurate, etc. 
        // this may not be true 
        // Also the tableIDs need to be assigned correctly, which 
        // TableSubsetWriter doesn't do. 
        LOG.info(String.format("Merging table %s into targetAIS", sourceTable.getName().toString()));

        final AISBuilder builder = new AISBuilder(targetAIS, nameGenerator);
        if(TableName.INFORMATION_SCHEMA.equals(sourceTable.getName().getSchemaName())) {
            builder.setTableIdOffset(getISTableIdOffset());
        } else {
            builder.setTableIdOffset(getUserTableIDOffset(sourceTable.getName()));
        }

        if (sourceTable.getParentJoin() != null) {
            String parentSchemaName = sourceTable.getParentJoin().getParent().getName().getSchemaName();
            String parentTableName = sourceTable.getParentJoin().getParent().getName().getTableName(); 
            UserTable parentTable = targetAIS.getUserTable(parentSchemaName, parentTableName);
            if (parentTable == null) {
                throw new JoinToUnknownTableException (sourceTable.getName(), new TableName(parentSchemaName, parentTableName));
            }
            builder.setIndexIdOffset(computeIndexIDOffset(targetAIS, parentTable.getGroup().getName()));
        }

        // Add the user table to the targetAIS
        addTable (builder, sourceTable); 

        // Joins or group table?
        if (sourceTable.getParentJoin() == null) {
            LOG.debug("Table is root or lone table");
            String groupName = nameGenerator.generateGroupName(sourceTable);
            String groupTableName = nameGenerator.generateGroupTableName(groupName);
            builder.basicSchemaIsComplete();            
            builder.createGroup(groupName, 
                    sourceTable.getName().getSchemaName(), 
                    groupTableName);
            builder.addTableToGroup(groupName, 
                    sourceTable.getName().getSchemaName(), 
                    sourceTable.getName().getTableName());
        } else {
            // Normally there should be only one candidate parent join.
            // But since the AIS supports multiples, so does the merge.
            // This gets flagged in JoinToOneParent validation. 
            for (Join join : sourceTable.getCandidateParentJoins()) {
                addJoin (builder, join);
            }
        }
        builder.groupingIsComplete();
        
        builder.akibanInformationSchema().validate(AISValidations.LIVE_AIS_VALIDATIONS).throwIfNecessary();
        builder.akibanInformationSchema().freeze();
        return this;
    }

    private void addTable(AISBuilder builder, final UserTable table) {
        
        // I should use TableSubsetWriter(new AISTarget(targetAIS)) or AISCloner.clone()
        // but both assume the UserTable.getAIS() is complete and valid. 
        // i.e. has a group and group table, and the joins point to a valid table
        // which, given the use of AISMerge, is not true. 
        
        
        final String schemaName = table.getName().getSchemaName();
        final String tableName = table.getName().getTableName();
        

        builder.userTable(schemaName, tableName);
        UserTable targetTable = targetAIS.getUserTable(schemaName, tableName); 
        targetTable.setEngine(table.getEngine());
        targetTable.setCharsetAndCollation(table.getCharsetAndCollation());
        
        // columns
        for (Column column : table.getColumns()) {
            builder.column(schemaName, tableName, 
                    column.getName(), column.getPosition(), 
                    column.getType().name(), 
                    column.getTypeParameter1(), column.getTypeParameter2(), 
                    column.getNullable(), 
                    column.getInitialAutoIncrementValue() != null, 
                    column.getCharsetAndCollation().charset(), 
                    column.getCharsetAndCollation().collation());
            Column newColumn = targetTable.getColumn(column.getPosition());
            // if an auto-increment column, set the starting value. 
            if (column.getInitialAutoIncrementValue() != null) {
                newColumn.setInitialAutoIncrementValue(column.getInitialAutoIncrementValue());
            }
            if (column.getDefaultIdentity() != null) {
                String sequenceName = nameGenerator.generateIdentitySequenceName(new TableName(schemaName, tableName));
                Sequence sequence = column.getIdentityGenerator();
                builder.sequence(schemaName, sequenceName, 
                        sequence.getStartsWith(), 
                        sequence.getIncrement(), 
                        sequence.getMinValue(), 
                        sequence.getMaxValue(), 
                        sequence.isCycle());
                builder.columnAsIdentity(schemaName, tableName, column.getName(), sequenceName, column.getDefaultIdentity());
                LOG.debug("Generated sequence: {}, with tree name; {}", sequenceName, sequence.getTreeName());
            }
            // Proactively cache, can go away if Column ever cleans itself up
            newColumn.getMaxStorageSize();
            newColumn.getPrefixSize();
        }
        
        // indexes/constraints
        for (TableIndex index : table.getIndexes()) {
            IndexName indexName = index.getIndexName();
            
            builder.index(schemaName, tableName, 
                    indexName.getName(), 
                    index.isUnique(), 
                    index.getConstraint());
            for (IndexColumn col : index.getKeyColumns()) {
                    builder.indexColumn(schemaName, tableName, index.getIndexName().getName(),
                        col.getColumn().getName(), 
                        col.getPosition(), 
                        col.isAscending(), 
                        col.getIndexedLength());
            }
        }
    }

    private void addJoin (AISBuilder builder, Join join) {
        String parentSchemaName = join.getParent().getName().getSchemaName();
        String parentTableName = join.getParent().getName().getTableName();
        UserTable parentTable = targetAIS.getUserTable(parentSchemaName, parentTableName);
        if (parentTable == null) {
            throw new JoinToUnknownTableException(sourceTable.getName(), new TableName(parentSchemaName, parentTableName));
         }
        LOG.debug(String.format("Table is child of table %s", parentTable.getName().toString()));
        String joinName = nameGenerator.generateJoinName(parentTable.getName(),
                                                         sourceTable.getName(),
                                                         join.getJoinColumns());
        builder.joinTables(joinName,
                parentSchemaName,
                parentTableName,
                sourceTable.getName().getSchemaName(), 
                sourceTable.getName().getTableName());

        for (JoinColumn joinColumn : join.getJoinColumns()) {
            try {
            builder.joinColumns(joinName, 
                    parentSchemaName, 
                    parentTableName, 
                    joinColumn.getParent().getName(),
                    sourceTable.getName().getSchemaName(), 
                    sourceTable.getName().getTableName(), 
                    joinColumn.getChild().getName());
            } catch (AISBuilder.NoSuchObjectException ex) {
                throw new JoinToWrongColumnsException (
                        sourceTable.getName(), joinColumn.getChild().getName(),
                        new TableName(parentSchemaName, parentTableName),
                        joinColumn.getParent().getName());
            }
        }
        builder.basicSchemaIsComplete();
        
        try {
            builder.addJoinToGroup(parentTable.getGroup().getName(), joinName, 0);
        } catch (AISBuilder.GroupStructureException ex) {
            throw new JoinToMultipleParentsException(join.getChild().getName());
        }
    }

    // FOR DEBUGGING
/*
    private void dumpGroupStructure(String label, AkibanInformationSchema ais)
    {
        for (Group group : ais.getGroups().values()) {
            if (!group.getGroupTable().getRoot().getName().getSchemaName().equals(TableName.INFORMATION_SCHEMA)) {
                System.out.println(String.format("%s: Group %s", label, group.getName()));
                System.out.println("    tables:");
                for (UserTable userTable : ais.getUserTables().values()) {
                    if (userTable.getGroup() == group) {
                        System.out.println(String.format("        %s -> %s", userTable, userTable.parentTable()));
                    }
                }
                System.out.println("    joins:");
                for (Join join : ais.getJoins().values()) {
                    if (join.getGroup() == group) {
                        System.out.println(String.format("        %s -> %s", join.getChild(), join.getParent()));
                    }
                }
            }
        }
    }
*/

    private int getUserTableIDOffset(TableName name) {
        int offset = getNextTableID(false);
        if(offset >= AIS_TABLE_ID_OFFSET) {
            LOG.warn("Offset for table {} unexpectedly large: {}", name, offset);
        }
        return offset;
    }

    private int getISTableIdOffset() {
        int offset = getNextTableID(true);
        assert offset >= AIS_TABLE_ID_OFFSET : "Offset too small for IS table: " + offset;
        return offset;
    }

    /**
     * Get the next number that could be used for a table ID. The parameter indicates
     * where to start the search, but the ID will be unique across ALL tables.
     * @param isISTable Offset to start the search at.
     * @return Unique ID value.
     */
    private int getNextTableID(boolean isISTable) {
        Integer nextID = (isISTable ? isTableIDSet.last() : userTableIDSet.last()) + 1;
        while(isTableIDSet.contains(nextID) || userTableIDSet.contains(nextID)) {
            nextID += 1;
        }
        return nextID;
    }

    private static int computeIndexIDOffset (AkibanInformationSchema ais, String groupName) {
        int offset = 1;
        Group group = ais.getGroup(groupName);
        for(UserTable table : ais.getUserTables().values()) {
            if(table.getGroup().equals(group)) {
                for(Index index : table.getIndexesIncludingInternal()) {
                    offset = Math.max(offset, index.getIndexId() + 1);
                }
            }
        }
        for (GroupIndex index : group.getIndexes()) {
            offset = Math.max(offset, index.getIndexId() + 1); 
        }
        return offset;
    }

    public static Set<String> computeTreeNames (AkibanInformationSchema ais) {
        // Collect all tree names
        Set<String> treeNames = new HashSet<String>();
        for(Group group : ais.getGroups().values()) {
            for(Index index : group.getIndexes()) {
                treeNames.add(index.getTreeName());
            }
        }
        for(UserTable table : ais.getUserTables().values()) {
            if(table.getParentJoin() == null) {
                treeNames.add(table.getTreeName());
            }
            for(Index index : table.getIndexesIncludingInternal()) {
                treeNames.add(index.getTreeName());
            }
        }
        for (Sequence sequence : ais.getSequences().values()){
            if(sequence.getTreeName() != null) {
                treeNames.add(sequence.getTreeName());
            }
        }
        return treeNames;
    }

    public static Set<String> computeSequenceNames (AkibanInformationSchema ais) {
        // Collect all sequence names
        Set<String> sequenceNames = new HashSet<String>();
        for(TableName sequence : ais.getSequences().keySet()) {
            sequenceNames.add(sequence.getTableName());
        }
        return sequenceNames;
    }

    private void collectTableIDs(AkibanInformationSchema ais) {
        userTableIDSet.clear();
        userTableIDSet.add(USER_TABLE_ID_OFFSET - 1);
        isTableIDSet.clear();
        isTableIDSet.add(AIS_TABLE_ID_OFFSET - 1);
        for(Schema schema : ais.getSchemas().values()) {
            final Set<Integer> set = TableName.INFORMATION_SCHEMA.equals(schema.getName()) ? isTableIDSet : userTableIDSet;
            for(UserTable table : schema.getUserTables().values()) {
                set.add(table.getTableId());
                Group group = table.getGroup();
                if(group != null && group.getGroupTable() != null) {
                    set.add(group.getGroupTable().getTableId());
                }
            }
        }
    }

    public static AkibanInformationSchema mergeView(AkibanInformationSchema oldAIS,
                                                    View view) {
        AkibanInformationSchema newAIS = copyAIS(oldAIS);
        copyView(newAIS, view);
        newAIS.validate(AISValidations.LIVE_AIS_VALIDATIONS).throwIfNecessary();
        newAIS.freeze();
        return newAIS;
    }

    public static void copyView(AkibanInformationSchema newAIS,
                                View oldView) {
        Map<TableName,Collection<String>> newReferences = 
            new HashMap<TableName,Collection<String>>();
        for (Map.Entry<TableName,Collection<String>> entry : oldView.getTableColumnReferences().entrySet()) {
            newReferences.put(entry.getKey(),
                              new HashSet<String>(entry.getValue()));
        }
        View newView = View.create(newAIS,
                                   oldView.getName().getSchemaName(),
                                   oldView.getName().getTableName(),
                                   oldView.getDefinition(),
                                   oldView.getDefinitionProperties(),
                                   newReferences);
        for (Column col : oldView.getColumns()) {
            Column.create(newView, col.getName(), col.getPosition(),
                          col.getType(), col.getNullable(),
                          col.getTypeParameter1(), col.getTypeParameter2(), 
                          col.getInitialAutoIncrementValue(),
                          col.getCharsetAndCollation());
        }
        newAIS.addView(newView);
    }
}
