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

final class GroupIndexHelper {

    // for use by package

    static void actOnGroupIndexTables(GroupIndex index, IndexColumn indexColumn, IndexAction action) {
        if (!indexColumn.getIndex().equals(index)) {
            throw new IllegalArgumentException("indexColumn must belong to index: " + indexColumn + "not of " + index);
        }
        UserTable userTable = indexColumn.getColumn().getUserTable();
        assert userTable.isUserTable() : "not a user table: " + userTable;
        action.act(index, userTable);
    }

    static void actOnGroupIndexTables(GroupIndex index, IndexAction action) {
        for (IndexColumn indexColumn : index.getKeyColumns()) {
            actOnGroupIndexTables(index, indexColumn, action);
        }
    }

    // nested classes
    private static interface IndexAction {
        void act(GroupIndex groupIndex, UserTable onTable);
    }

    // class state

    final static IndexAction REMOVE = new IndexAction() {
        @Override
        public void act(GroupIndex groupIndex, UserTable onTable) {
            UserTable ancestor = onTable;
            while(ancestor != null) {
                ancestor.removeGroupIndex(groupIndex);
                ancestor = ancestor.parentTable();
            }
        }

        @Override
        public String toString() {
            return "REMOVE";
        }
    };

    final static IndexAction ADD = new IndexAction() {
        @Override
        public void act(GroupIndex groupIndex, UserTable onTable) {
            UserTable ancestor = onTable;
            while(ancestor != null) {
                ancestor.addGroupIndex(groupIndex);
                ancestor = ancestor.parentTable();
            }
        }

        @Override
        public String toString() {
            return "ADD";
        }
    };
}
