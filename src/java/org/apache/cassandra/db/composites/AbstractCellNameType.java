/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.db.composites;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;

import com.google.common.collect.AbstractIterator;
import org.apache.cassandra.cql3.CQL3Row;
import org.apache.cassandra.cql3.ColumnIdentifier;
import org.apache.cassandra.db.*;
import org.apache.cassandra.db.filter.IDiskAtomFilter;
import org.apache.cassandra.db.filter.NamesQueryFilter;
import org.apache.cassandra.db.marshal.AbstractType;
import org.apache.cassandra.db.marshal.CollectionType;
import org.apache.cassandra.db.marshal.ColumnToCollectionType;
import org.apache.cassandra.io.ISerializer;
import org.apache.cassandra.io.IVersionedSerializer;
import org.apache.cassandra.utils.ByteBufferUtil;

public abstract class AbstractCellNameType extends AbstractCType implements CellNameType
{
    private final Comparator<Column> columnComparator;
    private final Comparator<Column> columnReverseComparator;
    private final Comparator<OnDiskAtom> onDiskAtomComparator;

    private final ISerializer<CellName> cellSerializer;
    private final ColumnSerializer columnSerializer;
    private final OnDiskAtom.Serializer onDiskAtomSerializer;
    private final IVersionedSerializer<NamesQueryFilter> namesQueryFilterSerializer;
    private final IVersionedSerializer<IDiskAtomFilter> diskAtomFilterSerializer;

    protected AbstractCellNameType()
    {
        columnComparator = new Comparator<Column>()
        {
            public int compare(Column c1, Column c2)
            {
                return AbstractCellNameType.this.compare(c1.name(), c2.name());
            }
        };
        columnReverseComparator = new Comparator<Column>()
        {
            public int compare(Column c1, Column c2)
            {
                return AbstractCellNameType.this.compare(c2.name(), c1.name());
            }
        };
        onDiskAtomComparator = new Comparator<OnDiskAtom>()
        {
            public int compare(OnDiskAtom c1, OnDiskAtom c2)
            {
                int comp = AbstractCellNameType.this.compare(c1.name(), c2.name());
                if (comp != 0)
                    return comp;

                if (c1 instanceof RangeTombstone)
                {
                    if (c2 instanceof RangeTombstone)
                    {
                        RangeTombstone t1 = (RangeTombstone)c1;
                        RangeTombstone t2 = (RangeTombstone)c2;
                        int comp2 = AbstractCellNameType.this.compare(t1.max, t2.max);
                        return comp2 == 0 ? t1.data.compareTo(t2.data) : comp2;
                    }
                    else
                    {
                        return -1;
                    }
                }
                else
                {
                    return c2 instanceof RangeTombstone ? 1 : 0;
                }
            }
        };

        // A trivial wrapped over the composite serializer
        cellSerializer = new ISerializer<CellName>()
        {
            public void serialize(CellName c, DataOutput out) throws IOException
            {
                serializer().serialize(c, out);
            }

            public CellName deserialize(DataInput in) throws IOException
            {
                Composite ct = serializer().deserialize(in);
                if (ct.isEmpty())
                    throw ColumnSerializer.CorruptColumnException.create(in, ByteBufferUtil.EMPTY_BYTE_BUFFER);

                assert ct instanceof CellName : ct;
                return (CellName)ct;
            }

            public long serializedSize(CellName c, TypeSizes type)
            {
                return serializer().serializedSize(c, type);
            }
        };
        columnSerializer = new ColumnSerializer(this);
        onDiskAtomSerializer = new OnDiskAtom.Serializer(this);
        namesQueryFilterSerializer = new NamesQueryFilter.Serializer(this);
        diskAtomFilterSerializer = new IDiskAtomFilter.Serializer(this);
    }

    public Comparator<Column> columnComparator()
    {
        return columnComparator;
    }

    public Comparator<Column> columnReverseComparator()
    {
        return columnReverseComparator;
    }

    public Comparator<OnDiskAtom> onDiskAtomComparator()
    {
        return onDiskAtomComparator;
    }

    public ISerializer<CellName> cellSerializer()
    {
        return cellSerializer;
    }

    public ColumnSerializer columnSerializer()
    {
        return columnSerializer;
    }

    public OnDiskAtom.Serializer onDiskAtomSerializer()
    {
        return onDiskAtomSerializer;
    }

    public IVersionedSerializer<NamesQueryFilter> namesQueryFilterSerializer()
    {
        return namesQueryFilterSerializer;
    }

    public IVersionedSerializer<IDiskAtomFilter> diskAtomFilterSerializer()
    {
        return diskAtomFilterSerializer;
    }

    public CellName cellFromByteBuffer(ByteBuffer bytes)
    {
        return (CellName)fromByteBuffer(bytes);
    }

    public CellName create(Composite prefix, ColumnIdentifier columnName, ByteBuffer collectionElement)
    {
        throw new UnsupportedOperationException();
    }

    public CellName rowMarker(Composite prefix)
    {
        throw new UnsupportedOperationException();
    }

    public boolean hasCollections()
    {
        return false;
    }

    public boolean supportCollections()
    {
        return false;
    }

    public ColumnToCollectionType collectionType()
    {
        throw new UnsupportedOperationException();
    }

    public CellNameType addCollection(ColumnIdentifier columnName, CollectionType newCollection)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public Composite make(Object... components)
    {
        return components.length == size() ? makeCellName(components) : super.make(components);
    }

    public CellName makeCellName(Object... components)
    {
        ByteBuffer[] rawComponents = new ByteBuffer[components.length];
        for (int i = 0; i < components.length; i++)
        {
            Object c = components[i];
            rawComponents[i] = c instanceof ByteBuffer ? (ByteBuffer)c : ((AbstractType)subtype(i)).decompose(c);
        }
        return makeCellName(rawComponents);
    }

    protected abstract CellName makeCellName(ByteBuffer[] components);

    protected static CQL3Row.Builder makeDenseCQL3RowBuilder(final long now)
    {
        return new CQL3Row.Builder()
        {
            public Iterator<CQL3Row> group(final Iterator<Column> cells)
            {
                return new AbstractIterator<CQL3Row>()
                {
                    protected CQL3Row computeNext()
                    {
                        while (cells.hasNext())
                        {
                            final Column cell = cells.next();
                            if (cell.isMarkedForDelete(now))
                                continue;

                            return new CQL3Row()
                            {
                                public ByteBuffer getClusteringColumn(int i)
                                {
                                    return cell.name().get(i);
                                }

                                public Column getColumn(ColumnIdentifier name)
                                {
                                    return cell;
                                }

                                public List<Column> getCollection(ColumnIdentifier name)
                                {
                                    return null;
                                }
                            };
                        }
                        return endOfData();
                    }
                };
            }
        };
    }

    protected static CQL3Row.Builder makeSparseCQL3RowBuilder(final long now)
    {
        return new CQL3Row.Builder()
        {
            public Iterator<CQL3Row> group(final Iterator<Column> cells)
            {
                return new AbstractIterator<CQL3Row>()
                {
                    private CellName previous;
                    private CQL3RowOfSparse currentRow;

                    protected CQL3Row computeNext()
                    {
                        while (cells.hasNext())
                        {
                            final Column cell = cells.next();
                            if (cell.isMarkedForDelete(now))
                                continue;

                            CQL3Row toReturn = null;
                            CellName current = cell.name();
                            if (currentRow == null || !current.isSameCQL3RowAs(previous))
                            {
                                toReturn = currentRow;
                                currentRow = new CQL3RowOfSparse(current);
                            }
                            currentRow.add(cell);
                            previous = current;

                            if (toReturn != null)
                                return toReturn;
                        }
                        if (currentRow != null)
                        {
                            CQL3Row toReturn = currentRow;
                            currentRow = null;
                            return toReturn;
                        }
                        return endOfData();
                    }
                };
            }
        };
    }

    private static class CQL3RowOfSparse implements CQL3Row
    {
        private final CellName cell;
        private Map<ColumnIdentifier, Column> columns;
        private Map<ColumnIdentifier, List<Column>> collections;

        CQL3RowOfSparse(CellName cell)
        {
            this.cell = cell;
        }

        public ByteBuffer getClusteringColumn(int i)
        {
            return cell.get(i);
        }

        void add(Column cell)
        {
            CellName cellName = cell.name();
            ColumnIdentifier columnName =  cellName.cql3ColumnName();
            if (cellName.isCollectionCell())
            {
                if (collections == null)
                    collections = new HashMap<>();

                List<Column> values = collections.get(columnName);
                if (values == null)
                {
                    values = new ArrayList<Column>();
                    collections.put(columnName, values);
                }
                values.add(cell);
            }
            else
            {
                if (columns == null)
                    columns = new HashMap<>();
                columns.put(columnName, cell);
            }
        }

        public Column getColumn(ColumnIdentifier name)
        {
            return columns == null ? null : columns.get(name);
        }

        public List<Column> getCollection(ColumnIdentifier name)
        {
            return collections == null ? null : collections.get(name);
        }
    }
}
