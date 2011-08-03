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

package com.akiban.server.test.it.rowtests;

import com.akiban.server.FieldDef;
import com.akiban.server.RowData;
import com.akiban.server.RowDef;
import com.akiban.server.error.InvalidOperationException;
import com.akiban.server.test.it.ITBase;
import junit.framework.Assert;
import org.junit.Ignore;
import org.junit.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;

import static org.junit.Assert.assertNotNull;

public class FieldToFromObjectIT extends ITBase {
    private final RowData rowData = new RowData(new byte[4096]);
    
    private void testRow(final RowDef rowDef, Object ...values) {
        rowData.reset(0, rowData.getBytes().length);
        try {
            rowData.createRow(rowDef, values);
        }
        catch(Exception e) {
            Assert.fail(String.format("createRow() failed for table %s and values %s\n\n%s",
                                      rowDef.table().getName(), Arrays.asList(values), e));
        }

        int curField = 0;
        int maxField = rowDef.getFieldCount();
        byte[] tmpArray = new byte[100];
        try {
            while(curField < maxField) {
                Object value = rowData.toObject(rowDef, curField);
                FieldDef fieldDef = rowDef.getFieldDef(curField);
                fieldDef.getEncoding().fromObject(fieldDef, value,  tmpArray, 0);
                ++curField;
            }
        }
        catch(Exception e) {
            Assert.fail(String.format("toObject/fromObject failed for table %s, field %s, original value %s\n\n%s",
                                      rowDef.table().getName(), rowDef.getFieldDef(curField), values[curField], e));
        }
    }

    @Test
    public void signedIntTypes() throws InvalidOperationException {
        final int tid = createTable("test", "t", "id int key", "c2 tinyint, c3 smallint, c4 mediumint, c5 int, c6 bigint");
        final RowDef def = rowDefCache().getRowDef(tid);
        testRow(def, 1, 0, 0, 0, 0, 0);                                              // zero
        testRow(def, 2, -128, -32768, -8388608, -2147483648, -9223372036854775808L); // min
        testRow(def, 3, 127, 32767, 8388607, 2147483647, 9223372036854775807L);      // max
        testRow(def, 4, 35, 23964, 7904325, -148932453, 86937294587L);               // other
    }

    @Test
    public void unsignedIntTypes() throws InvalidOperationException {
        final int tid = createTable("test", "t", "id int key", "c2 tinyint unsigned, c3 smallint unsigned",
                                    "c4 mediumint unsigned, c5 int unsigned, c6 bigint unsigned");
        final RowDef def = rowDefCache().getRowDef(tid);
        testRow(def, 1, 0, 0, 0, 0, 0);                                                             // zero/min
        testRow(def, 2, 255, 65535, 16777215, 4294967295L, new BigInteger("18446744073709551615")); // max
        testRow(def, 3, 42, 9848, 2427090, 290174268L, 73957261119487228L);                         // other
    }

    @Test
    public void signedRealTypes() throws InvalidOperationException {
        final int tid = createTable("test", "t", "id int key", "c2 float, c3 double");
        final RowDef def = rowDefCache().getRowDef(tid);
        testRow(def, 1, 0, 0);                              // zero
        testRow(def, 2, Float.MIN_VALUE, Double.MIN_VALUE); // min
        testRow(def, 3, Float.MAX_VALUE, Double.MAX_VALUE); // max
        testRow(def, 4, -10f, -100d);                       // negative whole
        testRow(def, 5, -123.456f, -8692.18474823d);        // negative fraction
        testRow(def, 6, 432f, 398483298d);                  // positive whole
        testRow(def, 7, 75627.872743f, 99578294.938483d);   // positive fraction
    }

    @Test
    public void unsignedRealTypes() throws InvalidOperationException {
        final int tid = createTable("test", "t", "id int key", "c2 float unsigned, c3 double unsigned");
        final RowDef def = rowDefCache().getRowDef(tid);
        testRow(def, 1, 0, 0);                              // zero
        testRow(def, 2, Float.MAX_VALUE, Double.MAX_VALUE); // max
        testRow(def, 3, 12345f, 9876543210d);               // positive whole
        testRow(def, 4, 7234.1321f, 3819476924.12342819d);  // positive fraction
    }

    @Test
    public void decimalTypes() throws InvalidOperationException {
        final int tid = createTable("test", "t", "id int key", "c2 decimal(5,2), c3 decimal(5,2) unsigned");
        final RowDef def = rowDefCache().getRowDef(tid);
        testRow(def, 1, BigDecimal.valueOf(0), BigDecimal.valueOf(0));                // zero
        testRow(def, 2, BigDecimal.valueOf(-99999L, 2), BigDecimal.valueOf(0));       // min
        testRow(def, 3, BigDecimal.valueOf(99999L, 2), BigDecimal.valueOf(99999, 2)); // max
        testRow(def, 4, BigDecimal.valueOf(-999L), BigDecimal.valueOf(0));            // negative whole
        testRow(def, 5, BigDecimal.valueOf(-12345, 1), BigDecimal.valueOf(0));        // negative fraction
        testRow(def, 6, BigDecimal.valueOf(567L), BigDecimal.valueOf(10));            // positive whole
        testRow(def, 7, BigDecimal.valueOf(425, 1), BigDecimal.valueOf(76543, 2));    // positive fraction
    }

    @Test
    public void charTypes() throws InvalidOperationException {
        final int tid = createTable("test", "t", "id int key", "c2 char(10), c1 varchar(26)");
        final RowDef def = rowDefCache().getRowDef(tid);
        testRow(def, 1, "", "");                                     // empty
        testRow(def, 2, "0123456789", "abcdefghijklmnopqrstuvwxyz"); // full
        testRow(def, 3, "zebra", "see spot run");                    // other
    }

    @Test
    public void blobTypes() throws InvalidOperationException {
        final int tid = createTable("test", "t", "id int key", "c1 tinyblob, c2 blob, c3 mediumblob, c4 longblob");
        final RowDef def = rowDefCache().getRowDef(tid);
        testRow(def, 1, "", "", "", "");            // empty
        testRow(def, 2, "a", "bc", "def", "hijk");  // other
    }

    @Test
    public void textTypes() throws InvalidOperationException {
        final int tid = createTable("test", "t", "id int key", "c1 tinytext, c2 text, c3 mediumtext, c4 longtext");
        final RowDef def = rowDefCache().getRowDef(tid);
        testRow(def, 1, "", "", "", "");            // empty
        testRow(def, 2, "1", "23", "456", "7890");  // other
    }

    @Test
    public void binaryTypes() throws InvalidOperationException {
        final int tid = createTable("test", "t", "id int key", "c1 binary(10), c2 varbinary(26)");
        final RowDef def = rowDefCache().getRowDef(tid);
        final byte[] emptyArr = {};
        final byte[] partialArr5 = {1, 2, 3, 4, 5};
        final byte[] partialArr15 = {-24, 8, -98, 45, 67, 127, 34, -34, -42, 9, 10, 100, 57, -20, 5};
        final byte[] fullArr10 = {0, -10, 24, -128, 127, 83, 97, 100, -120, 42};
        final byte[] fullArr26 = {-47, -48, -66, 98, -54, -12, -53, -100, -25, -80, 9, 29, 17, -39, 45, 44, 15,
                                    8, -35, 119, -83, 32, -119, 17, 126, -112};
        testRow(def, 1, emptyArr, emptyArr);
        testRow(def, 2, fullArr10, fullArr26);
        testRow(def, 3, partialArr5, partialArr15);
    }

    @Test
    public void dateAndTimeTypes() throws InvalidOperationException {
        final int tid = createTable("test", "t", "id int key", "c1 date, c2 time, c3 datetime, c4 timestamp, c5 year");
        final RowDef def = rowDefCache().getRowDef(tid);
        testRow(def, 1, "0000-00-00", "00:00:00", "0000-00-00 00:00:00", 0, "0000");           // zero
        testRow(def, 2, "1000-01-01", "-838:59:59", "1000-01-01 00:00:00", 0, "1901");         // min
        testRow(def, 3, "9999-12-31", "838:59:59", "9999-12-31 23:59:59", 2147483647, "2155"); // max
        testRow(def, 4, "2011-05-20", "17:34:20", "2011-05-20 17:35:01", 1305927301, "2011");  // other
    }
}
