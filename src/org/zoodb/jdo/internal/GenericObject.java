/*
 * Copyright 2009-2013 Tilmann Z�schke. All rights reserved.
 * 
 * This file is part of ZooDB.
 * 
 * ZooDB is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * ZooDB is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with ZooDB.  If not, see <http://www.gnu.org/licenses/>.
 * 
 * See the README and COPYING files for further information. 
 */
package org.zoodb.jdo.internal;

import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;

import org.zoodb.jdo.internal.client.PCContext;
import org.zoodb.jdo.internal.server.ObjectReader;
import org.zoodb.jdo.internal.server.index.BitTools;

/**
 * Instances of this class represent persistent instances that can not be de-serialised into
 * Java classes, because according Java classes do not exist. This can for example occur during
 * schema evolution. 
 * 
 * Generating required classes is not always possible, because there may be a class with the 
 * required name but with a wrong set of attributes.
 * TODO a solution would be to generate temporary Java classes for de-serialisation, using
 * a temporary name such as Zoo__Generic__[ClassName].
 * 
 * Since we have to insert/delete attributes, we need to split them up 
 * (otherwise, just mark them as deleted? how about inserted???).
 * 
 * Uses:
 * - One aim is to return a bitstream with the correct schema (transport to client)
 * - Store locally with updated schema -> requires updated bit-stream
 * - Alternatively, allow reading as if the schema was modified (through transparent mapping)???
 * 
 * What should the output be:
 * - A bitstream: Can be transferred to client (or server), can be directly stored or fed to
 *   DeSerialiser.
 * - A generic object that allows random access? This could also be achieved by using a bitstream
 *   and the NoClass(De)Serializer.
 * 
 * Output: BIT-STREAM!
 * TODO rename to SchemaMapper? Input: byte[]; output: evolved byte[]
 * 
 * However, byte[] is not ideal for random access (updates). How do we do updates? Updates 
 * require resizing (e.g. String). This could be done on-the-fly on a byte[] or afterwards
 * using an existing Serializer (?); not quite, an existing Serializer takes real objects as input,
 * not serialised ones (important for SCOs?!?!?).
 * 
 *  
 * How is deserialisation different?
 * - Assigning values:
 *   - values are assigned to an Object[] instead of Field instances. This affects only 
 *     readPrimitive() and deserializeFields1()/2().
 * - References:
 *   - FCOs (hollowToObject()) returns a Long instead of an PCImpl
 *   - SCOs return a byte[] instead of Object.
 *   
 * --> CHECK Looks like we could integrate thus into the existing deserializer... 
 *  
 * This is clear:
 * - Input: Bit-Stream, SchemaDefinition, SchemaMapping
 * - Capability: Mapping (on-the-fly or permanent evolution) of schemata.
 * - Output: ?
 * 
 * - Input #2: Updates to fields
 * - Output #2: Individual fields
 * - I/O #2: Could be primitives, but also byte[] for SCOs!!!!
 * 
 * @author Tilmann Zaschke
 */
public class GenericObject {

    private ZooClassDef def;
    private ZooClassDef defOriginal;
	private long oid;
	//TODO keep in single ba[]?!?!?
	private Object[] fixedValues;
	private Object[] variableValues;
	//the context contains the original ClassDef.
	private final PCContext context;
	
	private boolean isDirty = false;
	private boolean isDeleted = false;
	private boolean isNew = false;
	
	public GenericObject(ZooClassDef def, long oid) {
		this.def = def;
		this.defOriginal = def;
		this.oid = oid;
		this.context = def.getProvidedContext();
		fixedValues = new Object[def.getAllFields().length];
		variableValues = new Object[def.getAllFields().length];
	}
	
	/**
	 * Creates new instances.
	 * @param def
	 * @return A new empty generic object.
	 */
	static GenericObject newEmptyInstance(ZooClassDef def) {
		long oid = def.getProvidedContext().getNode().getOidBuffer().allocateOid();
		GenericObject go = new GenericObject(def, oid);
		go.setNew(true);
		go.setDirty(true);
	
		//initialise
		//We do not use default values here, because we are not evolving objects (is that a 
		//good reason?)
		for (ZooFieldDef f: def.getAllFields()) {
			if (f.isPrimitiveType()) {
				Object x;
				switch (f.getPrimitiveType()) {
				case BOOLEAN: x = false; break;
				case BYTE: x = (byte)0; break;
				case CHAR: x = (char)0; break;
				case DOUBLE: x = (double)0; break;
				case FLOAT: x = (float)0; break;
				case INT: x = (int)0; break;
				case LONG: x = (long)0; break;
				case SHORT: x = (short)0; break;
				default: throw new IllegalStateException();
				}
				go.setField(f, x);
			}
			//else use 'null'
		}
		
		return go;
	}
	
	public void setFieldRAW(int i, Object deObj) {
		fixedValues[i] = deObj;
	}

	public Object getFieldRaw(int i) {
		return fixedValues[i];
	}

	public void setFieldRawSCO(int i, Object deObj) {
		variableValues[i] = deObj;
	}

	public void setField(ZooFieldDef fieldDef, Object val) {
		int i = fieldDef.getFieldPos();
		switch (fieldDef.getJdoType()) {
		case ARRAY:
		case BIG_DEC:
		case BIG_INT:
		case DATE:
		case NUMBER:
			throw new UnsupportedOperationException();
		case PRIMITIVE:
			try {
				//this ensures the correct type
				Object x;
				switch (fieldDef.getPrimitiveType()) {
				case BOOLEAN: x = val; break;
				case BYTE: x = (Byte)val; break;
				case CHAR: x = (Character)val; break;
				case DOUBLE: x = (Double)val; break;
				case FLOAT: x = (Float)val; break;
				case INT: x = (Integer)val; break;
				case LONG: x = (Long)val; break;
				case SHORT: x = (Short)val; break;
				default: throw new IllegalStateException();
				}
				fixedValues[i] = x;
				break;
			} catch (ClassCastException e) {
				throw new IllegalArgumentException("Value is of wrong type. Expected " +
						fieldDef.getPrimitiveType() + " but was " + val.getClass());
			}
		case REFERENCE:
		case SCO:
			throw new UnsupportedOperationException();
		case STRING:
			fixedValues[i] = BitTools.toSortableLong((String)val);
			variableValues[i] = val;
			break;
		}
	}

	/**
	 * 
	 * Returns OIDs for references.
	 * 
	 * @param fieldDef
	 * @return The value of that field.
	 */
	public Object getField(ZooFieldDef fieldDef) {
		int i = fieldDef.getFieldPos();
		switch (fieldDef.getJdoType()) {
		case ARRAY:
		case BIG_DEC:
		case BIG_INT:
			return variableValues[i];
		case DATE:
			return new Date((Long)fixedValues[i]);
		case NUMBER:
			throw new UnsupportedOperationException(fieldDef.getTypeName());
		case PRIMITIVE: return fixedValues[i];
		case REFERENCE: return fixedValues[i];
		case SCO:
		case STRING:
			return variableValues[i];
			default: throw new IllegalArgumentException();
		}
	}

	public ZooClassDef ensureLatestVersion() {
	    while (def.getNextVersion() != null) {
	        def = evolve();
	    }
	    return def;
	}
	
	private ZooClassDef evolve() {
		//TODO this is horrible!!!
		ArrayList<Object> fV = new ArrayList<Object>(Arrays.asList(fixedValues));
		ArrayList<Object> vV = new ArrayList<Object>(Arrays.asList(variableValues));
		
		//TODO resize only once to correct size
		for (PersistentSchemaOperation op: def.getNextVersion().getEvolutionOps()) {
			if (op.isAddOp()) {
				fV.add(op.getFieldId(), op.getInitialValue());
				vV.add(op.getFieldId(), null);
			} else {
				fV.remove(op.getFieldId());
				vV.remove(op.getFieldId());
			}
		}
		fixedValues = fV.toArray(fixedValues);
		variableValues = vV.toArray(variableValues);
		def = def.getNextVersion();
		return def;
	}

	public ObjectReader toStream() {
	    System.err.println("FIXME: Size of generic object writer");
	    //TODO, this is so dirty...  If the buffer is to small, we retry with a bigger one
	    int size = 1000;
	    ByteBuffer ba = null;
	    while (ba == null) {
		    try {
		    	GenericObjectWriter gow = new GenericObjectWriter(size, def.getOid());
				gow.newPage();
				DataSerializer ds = new DataSerializer(gow, 
				        context.getSession().internalGetCache(), 
				        context.getNode());
				ds.writeObject(this, def);
				ba = gow.toByteArray();
		    } catch (BufferOverflowException e) {
		    	//ignore, hehe...
			    size = size * 10;
			    System.err.println("FIXME: Resizing buffer!!!! " + size);
		    }
	    }
		ba.rewind();
		return new ObjectReader(new GenericObjectReader(ba));
	}

	public long getOid() {
		return oid;
	}

    public void setOid(long oid) {
        this.oid = oid;
    }

    public void setDirty(boolean b) {
        if (!isDirty) {
            isDirty = true;
            context.getSession().internalGetCache().addGeneric(this);
        }
    }
    
    public boolean isDirty() {
        return isDirty;
    }

    public ZooClassDef getClassDef() {
        return def;
    }

    public ZooClassDef getClassDefOriginal() {
        return defOriginal;
    }

	public void setClassDefOriginal(ZooClassDef defOriginal) {
		this.defOriginal = defOriginal;
	}

	public void setDeleted(boolean b) {
		this.isDeleted = b;
		if (b) {
			setDirty(true);
		}
	}

	public boolean isDeleted() {
		return isDeleted;
	}

	public boolean isNew() {
		return isNew ;
	}

	private void setNew(boolean b) {
		isNew = b;
	}

}
