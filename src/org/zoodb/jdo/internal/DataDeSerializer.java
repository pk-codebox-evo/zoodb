/*
 * Copyright 2009-2011 Tilmann Z�schke. All rights reserved.
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

import java.io.File;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.jdo.ObjectState;

import org.zoodb.api.impl.ZooPCImpl;
import org.zoodb.jdo.api.DBArrayList;
import org.zoodb.jdo.api.DBCollection;
import org.zoodb.jdo.api.DBHashMap;
import org.zoodb.jdo.api.DBLargeVector;
import org.zoodb.jdo.api.impl.DBStatistics;
import org.zoodb.jdo.internal.SerializerTools.PRIMITIVE;
import org.zoodb.jdo.internal.client.AbstractCache;
import org.zoodb.jdo.internal.server.ObjectReader;
import org.zoodb.jdo.internal.util.Util;
import org.zoodb.profiling.ProfilingConfig;
import org.zoodb.profiling.api.impl.ProfilingManager;


/**
 * This class creates instances from a byte stream. All classes that are 
 * processed with this class need to have a default constructor. The 
 * constructor can be all of public, protected, default and private.
 * <p>
 * Treatment of DBParentAware: <br>
 * There are some serious problems with classes that are DBParentAware. They
 * are causing a vicious circle:<br>
 * 1) Full-copy requires the ability to set fields of objects to reference 
 * other objects that have not yet been copied. This this is solved via Dummy 
 * objects. <br>
 * 2) At the point of making the object persistent, we need to know the name
 * of the target database. This name is derived via the DestinationRegistry. 
 * <br>
 * 3) For DBParentAware, the database name depends on the parent object.
 * The parent object cannot be guaranteed to exist (copied earlier, or in 
 * same tx). <br>
 * 4) So to make a DBHashtable persistent, we require the parent object to be
 * persistent, which is difficult to archive. It can be impossible in case the
 * parent is e.g. DBVector, which is copied later. <br>
 * Solution: For DBParentAware, we copy the ClassID of the parent. That is not 
 * the immediate parent, but the one that is not DBParentAware itself.<br>
 * This requires all Destinations (except DBParentAware) to be class based.
 * 
 * 
 * @author Tilmann Zaeschke
 */
public class DataDeSerializer {

    private final ObjectReader in;
    
    //Here is how class information is transmitted:
    //If the class does not exist in the hashMap, then it is added and its 
    //name is written to the stream. Otherwise only the id of the class in the 
    //List in written.
    //The class information is required because it can be any sub-type of the
    //Field type, but the exact type is required for instantiation.
    private final ArrayList<Class<?>> usedClasses = new ArrayList<Class<?>>(20);

    //Using static concurrent Maps seems to be 30% faster than non-static local maps that are 
    //created again and again.
    private static final ConcurrentHashMap<Class<?>, Constructor<?>> DEFAULT_CONSTRUCTORS = 
        new ConcurrentHashMap<Class<?>, Constructor<?>>(100);
    
    private final AbstractCache cache;
    private final Node node;
    
    //Cached Sets and Maps
    //The maps and sets are only filled after the keys have been de-serialized. Otherwise 
    //the keys will be inserted with a wrong hash value.
    //TODO load MAPS and SETS in one go and load all keys right away!
    //TODO or do not use add functionality, but serialize internal arrays right away! Probably does
    //not work for mixtures of LinkedLists and set like black-whit tree. (?).
    private final ArrayList<MapValuePair> mapsToFill = new ArrayList<MapValuePair>(5);
    private final ArrayList<SetValuePair> setsToFill = new ArrayList<SetValuePair>(5);
    private static class MapEntry { 
        Object K; Object V; 
        public MapEntry(Object key, Object value) {
            K = key;
            V = value;
        }
    }
    private static class MapValuePair { 
        Map<Object, Object> map; 
        MapEntry[] values; 
        public MapValuePair(Map<Object, Object> map, MapEntry[] values) {
            this.map = map;
            this.values = values;
        }
    }
    private static class SetValuePair { 
        Set<Object> set; 
        Object[] values; 
        public SetValuePair(Set<Object> set, Object[] values) {
        	this.set = set;
        	this.values = values;
        }
    }
    
    /**
     * Create a new DataDeserializer.
     * @param in Stream to read the data from.
     * persistent.
     */
    public static int N = 0;
    public DataDeSerializer(ObjectReader in, AbstractCache cache, Node node) {
        this.in = in;
        this.cache = cache;
        this.node = node;
    }


	/**
     * This method returns an object that is read from the input 
     * stream.
     * @param page 
     * @param offs 
     * @return The read object.
     */
    public ZooPCImpl readObject(int page, int offs, boolean skipIfCached) {
        long clsOid = in.startReading(page, offs);

        //Read first object:
    	long oid = in.readLong();

    	//check cache
       	ZooPCImpl pc = cache.findCoByOID(oid);
    	if (skipIfCached && pc != null) {
    	    if (pc.jdoZooIsDeleted() || !pc.jdoZooIsStateHollow()) {
    	        //isDeleted() are filtered out later.
    	        return pc;
    	    }
    	}

    	ZooClassDef clsDef = cache.getSchema(clsOid);
        ZooPCImpl pObj = getInstance(clsDef, oid, pc);
        //profiler
        pObj.setPageId(page);
        //end profiler
        return readObjPrivate(pObj, oid, clsDef);
    }
    
    
    public ZooPCImpl readObject(ZooPCImpl pc, int page, int offs) {
        long clsOid = in.startReading(page, offs);
    	
        //Read first object:
    	long oid = in.readLong();
    	
    	ZooClassDef clsDef = cache.getSchema(clsOid);
    	pc.jdoZooMarkClean();
    	//profiler
    	pc.setPageId(page);
    	//end profiler
        return readObjPrivate(pc, oid, clsDef);
    }
    
    
    private ZooPCImpl readObjPrivate(ZooPCImpl pObj, long oid, ZooClassDef clsDef) {
    	in.resetByteReadCounter();
    	// read first object (FCO)
        deserializeFields1( pObj, clsDef );
        deserializeFields2( pObj, clsDef );
        
        //read special classes
        if (pObj instanceof DBCollection) {
        	deserializeSpecial( pObj );
        }

        //Rehash collections. We have to do add all keys again, 
        //because when the collections were first de-serialised, the keys may
        //not have been de-serialised yet (if persistent) therefore their
        //hash-code may have been wrong.
        for (SetValuePair sv: setsToFill) {
            sv.set.clear();
            for (Object o: sv.values) {
                sv.set.add(o);
            }
        }
        setsToFill.clear();
        for (MapValuePair mv: mapsToFill) {
            //TODO NPE may occur because of skipping elements in deserializeHashTable (line 711)
            mv.map.clear();
            for (MapEntry e: mv.values) {
                mv.map.put(e.K, e.V);
            }
        }
        mapsToFill.clear();
        usedClasses.clear();
        //pObj.setTotalReadEffort(in.getByteReadCounter());
        //update class statistics
        reportFieldSizeRead(pObj,in.getByteReadCounter(),null);
        return pObj;
    }
    
    private final ZooPCImpl getInstance(ZooClassDef clsDef, long oid,
            ZooPCImpl co) {
            
    	if (co != null) {
    		//might be hollow!
    		co.jdoZooMarkClean();
    		return co;
        }
        
		Class<?> cls = clsDef.getJavaClass(); 
    	ZooPCImpl obj = (ZooPCImpl) createInstance(cls);
    	prepareObject(obj, oid, false, clsDef);
        return obj;
    }

    private final Object deserializeFields1(Object obj, ZooClassDef clsDef) {
        Field f1 = null;
        Object deObj = null;
        try {
            //Read fields
        	for (ZooFieldDef fd: clsDef.getAllFields()) {
                Field f = fd.getJavaField();
                f1 = f;
                PRIMITIVE prim = fd.getPrimitiveType();
                if (prim != null) {
                	long start = in.getByteReadCounter();
                	deserializePrimitive(obj, f, prim);
                	reportFieldSizeRead(obj,in.getByteReadCounter()-start,f);
                } else if (fd.isFixedSize()) {
                	long start = in.getByteReadCounter();
                	deObj = deserializeObjectNoSco(fd);
                	// exclude Strings, will be read in deserializeFields2
                	if (f.getType() != String.class) {
                		reportFieldSizeRead(obj,in.getByteReadCounter()-start,f);
                	}
                    f.set(obj, deObj);
                }
        	}
            return obj;
        } catch (IllegalArgumentException e) {
            throw new RuntimeException(e);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        } catch (SecurityException e) {
            throw new RuntimeException(e);
        } catch (BinaryDataCorruptedException e) {
            throw new BinaryDataCorruptedException("Corrupted Object: " +
                    //Util.getOidAsString(obj) + 
                    " " + clsDef + " F:" + 
                    f1 + " DO: " + (deObj != null ? deObj.getClass() : null), e);
        } catch (UnsupportedOperationException e) {
            throw new UnsupportedOperationException("Unsupported Object: " +
                    Util.getOidAsString(obj) + " " + clsDef + " F:" + 
                    f1 , e);
        }
    }

    private final Object deserializeFields2(Object obj, ZooClassDef clsDef) {
        Field f1 = null;
        Object deObj = null;
        try {
            //Read fields
        	for (ZooFieldDef fd: clsDef.getAllFields()) {
                if (!fd.isFixedSize() || fd.isString()) {
                	Field f = fd.getJavaField();
                	f1 = f;
                	
                	long start = in.getByteReadCounter();
                   	deObj = deserializeObjectSCO();
                   	reportFieldSizeRead(obj,in.getByteReadCounter()-start,f);
                    f.set(obj, deObj);
                }
        	}
            return obj;
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("Field: " + f1.getType() + " " + f1.getName(), e);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        } catch (SecurityException e) {
            throw new RuntimeException(e);
        } catch (BinaryDataCorruptedException e) {
            throw new BinaryDataCorruptedException("Corrupted Object: " +
                    Util.getOidAsString(obj) + " " + clsDef + " F:" + 
                    f1 + " DO: " + (deObj != null ? deObj.getClass() : null), e);
        } catch (UnsupportedOperationException e) {
            throw new UnsupportedOperationException("Unsupported Object: " +
                    Util.getOidAsString(obj) + " " + clsDef + " F:" + f1 , e);
        }
    }

    private final Object deserializeSCO(Object obj, Class<?> cls) {
        Field f1 = null;
        Object deObj = null;
        try {
            //Read fields
            for (Field field: SerializerTools.getFields(cls)) {
                f1 = field;
                if (field.getType().isPrimitive()) {
                    PRIMITIVE prim = SerializerTools.PRIMITIVE_TYPES.get(field.getType());
                	deserializePrimitive(obj, field, prim);
                } else {
                	deObj = deserializeObject();
                    field.set(obj, deObj);
                }
            }
            return obj;
        } catch (IllegalArgumentException e) {
            throw new RuntimeException(e);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        } catch (SecurityException e) {
            throw new RuntimeException(e);
        } catch (BinaryDataCorruptedException e) {
            throw new BinaryDataCorruptedException("Corrupted Object: " +
                    Util.getOidAsString(obj) + " " + cls + " F:" + 
                    f1 + " DO: " + (deObj != null ? deObj.getClass() : null), e);
        } catch (UnsupportedOperationException e) {
            throw new UnsupportedOperationException("Unsupported Object: " +
                    Util.getOidAsString(obj) + " " + cls + " F:" + 
                    f1 , e);
        }
    }

    @SuppressWarnings("unchecked")
    private final Object deserializeSpecial(Object obj) {
        try {
            //Special treatment for persistent containers.
            //Their data is not stored in (visible) fields.
            if (obj instanceof DBHashMap) {
                deserializeDBHashtable((DBHashMap<Object, Object>) obj);
            } else if (obj instanceof DBLargeVector) {
                deserializeDBLargeVector((DBLargeVector<Object>) obj);
            } else if (obj instanceof DBArrayList) {
                deserializeDBVector((DBArrayList<Object>) obj);
            }
            return obj;
        } catch (UnsupportedOperationException e) {
            throw new UnsupportedOperationException("Unsupported Object: " +
                    Util.getOidAsString(obj) + " " + obj.getClass(), e);
        }
    }

    private final void deserializePrimitive(Object parent, Field field, PRIMITIVE prim) 
            throws IllegalArgumentException, IllegalAccessException {
        switch (prim) {
        case BOOLEAN: field.setBoolean(parent, in.readBoolean()); break;
        case BYTE: field.setByte(parent, in.readByte()); break;
        case CHAR: field.setChar(parent, in.readChar()); break;
        case DOUBLE: field.setDouble(parent, in.readDouble()); break;
        case FLOAT: field.setFloat(parent, in.readFloat()); break;
        case INT: field.setInt(parent, in.readInt()); break;
        case LONG: field.setLong(parent, in.readLong()); break;
        case SHORT: field.setShort(parent, in.readShort()); break;
        default:
            throw new UnsupportedOperationException(prim.toString());
        }
    }        
             
    private final Object deserializeObjectNoSco(ZooFieldDef def) {
        //read class/null info
        Class<?> cls = readClassInfo();
        if (cls == null) {
        	in.skipRead(def.getLength()-1);
            //reference is null
            return null;
        }

        //read instance data
        if (def.isPersistentType()) {
            long oid = in.readLong();

            //Is object already in the database or cache?
            Object obj = hollowForOid(oid, cls);
            return obj;
        } else if (String.class == cls) {
        	in.readLong(); //read and ignore magic number
            return null;
        } else if (Date.class == cls) {
            return new Date(in.readLong());
        }

        throw new IllegalArgumentException("Illegal type: " + def.getName() + ": " + 
                def.getTypeName() + " in class " + def.getDeclaringType().getClassName());
    }

    @SuppressWarnings("unchecked")
    private final Object deserializeObjectSCO() {
        //read class/null info
        Class<?> cls = readClassInfo();
        if (cls == null) {
            //reference is null
            return null;
        }
        
        if (cls.isArray()) {
            return deserializeArray();
        }
        if (cls.isEnum()) {
        	return deserializeEnum();
        }
        
        PRIMITIVE p;
        
        //read instance data
        if (isPersistentCapableClass(cls)) {
        	//this can happen when we have a persistent object in a field of a non-persistent type
        	//like Object or possibly an interface
            long oid = in.readLong();

            //Is object already in the database or cache?
            Object obj = hollowForOid(oid, cls);
            return obj;
//        } else if (SerializerTools.PRIMITIVE_CLASSES.containsKey(cls)) {
        } else if ((p = SerializerTools.PRIMITIVE_CLASSES.get(cls)) != null) {
            return deserializeNumber(p);
        } else if (String.class == cls) {
            return deserializeString();
        } else if (Date.class == cls) {
        	throw new IllegalStateException();
        }
        
        if (Map.class.isAssignableFrom(cls)) {
            //ordered 
            int len = in.readInt();
            Map<Object, Object> m = (Map<Object, Object>) createInstance(cls);  //TODO sized?
            MapEntry[] values = new MapEntry[len];
            for (int i=0; i < len; i++) {
                //m.put(deserializeObject(), deserializeObject());
                //We don't fill the Map here.
                values[i] = new MapEntry(deserializeObject(), deserializeObject());
            }
            mapsToFill.add(new MapValuePair(m, values));
            return m;
        }
        if (Set.class.isAssignableFrom(cls)) {
            //ordered 
            int len = in.readInt();
            Set<Object> s = (Set<Object>) createInstance(cls);  //TODO sized?
            Object[] values = new Object[len];
            for (int i=0; i < len; i++) {
                //s.add(deserializeObject());
                //We don't fill the Set here.
                values[i] = deserializeObject();
            }
            setsToFill.add(new SetValuePair(s, values));
            return s;
        }
        //Check Iterable, Map, 'Array'  
        //This would include Vector and Hashtable
        if (Collection.class.isAssignableFrom(cls)) {
            Collection<Object> l = (Collection<Object>) createInstance(cls);  //TODO sized?
            //ordered 
            int len = in.readInt();
            for (int i=0; i < len; i++) {
                l.add(deserializeObject());
            }
            return l;
        }
        
        // TODO disallow? Allow Serializable/ Externalizable
        Object oo = deserializeSCO(createInstance(cls), cls);
        return oo;
    }

    /**
     * De-serialize objects. If the object is persistent capable, only it's OID
     * is stored. Otherwise it is serialized and the method is called
     * recursively on all of it's fields.
     * @return De-serialized value.
     */
    @SuppressWarnings("unchecked")
    private final Object deserializeObject() {
        //read class/null info
        Class<?> cls = readClassInfo();
        if (cls == null) {
            //reference is null
            return null;
        }
        
        if (cls.isArray()) {
            return deserializeArray();
        }
        if (cls.isEnum()) {
        	return deserializeEnum();
        }
       
        PRIMITIVE p;
        
        //read instance data
        if (isPersistentCapableClass(cls)) {
            long oid = in.readLong();

            //Is object already in the database or cache?
            Object obj = hollowForOid(oid, cls);
            return obj;
        } else if ((p = SerializerTools.PRIMITIVE_CLASSES.get(cls)) != null) {
            return deserializeNumber(p);
        } else if (String.class == cls) {
            return deserializeString();
        } else if (Date.class == cls) {
            return new Date(in.readLong());
        }
        
        if (Map.class.isAssignableFrom(cls)) {
            //ordered 
            int len = in.readInt();
            Map<Object, Object> m = (Map<Object, Object>) createInstance(cls);  //TODO sized?
            MapEntry[] values = new MapEntry[len];
            for (int i=0; i < len; i++) {
                //m.put(deserializeObject(), deserializeObject());
                //We don't fill the Map here.
                values[i] = new MapEntry(deserializeObject(), deserializeObject());
            }
            mapsToFill.add(new MapValuePair(m, values));
            return m;
        }
        if (Set.class.isAssignableFrom(cls)) {
            //ordered 
            int len = in.readInt();
            Set<Object> s = (Set<Object>) createInstance(cls);  //TODO sized?
            Object[] values = new Object[len];
            for (int i=0; i < len; i++) {
                //s.add(deserializeObject());
                //We don't fill the Set here.
                values[i] = deserializeObject();
            }
            setsToFill.add(new SetValuePair(s, values));
            return s;
        }
        //Check Iterable, Map, 'Array'  
        //This would include Vector and Hashtable
        if (Collection.class.isAssignableFrom(cls)) {
            Collection<Object> l = (Collection<Object>) createInstance(cls);  //TODO sized?
            //ordered 
            int len = in.readInt();
            for (int i=0; i < len; i++) {
                l.add(deserializeObject());
            }
            return l;
        }
        
        // TODO disallow? Allow Serializable/ Externalizable
        Object oo = deserializeSCO(createInstance(cls), cls);
        return oo;
    }

    private final Object deserializeNumber(PRIMITIVE prim) {
        switch (prim) {
        case BOOLEAN: return in.readBoolean();
        case BYTE: return in.readByte();
        case CHAR: return in.readChar();
        case DOUBLE: return in.readDouble();
        case FLOAT: return in.readFloat();
        case INT: return in.readInt();
        case LONG: return in.readLong();
        case SHORT: return in.readShort();
        default: throw new UnsupportedOperationException(
                "Class not supported: " + prim);
        }
    }
    
    private final Object deserializeEnum() {
        // read meta data
        Class<?> enumType = readClassInfo();
        short value = in.readShort();
		return enumType.getEnumConstants()[value];
    }

   private final Object deserializeArray() {
        
        // read meta data
        Class<?> innerType = readClassInfo();
        String innerTypeAcronym = deserializeString();
        
        short dims = in.readShort();
        
        // read data
        return deserializeArrayColumn(innerType, innerTypeAcronym, dims);
    }

    private final Object deserializeArrayColumn(Class<?> innerType, 
            String innerAcronym, int dims) {

        //read length
        int l = in.readInt();
        if (l == -1) {
            return null;
        }
        
        Object array = null;
        
        if (dims > 1) {
            //Create multi-dimensional array
            try {
                char[] ca = new char[dims-1];
                Arrays.fill(ca, '[');
                Class<?> compClass =  Class.forName(new String(ca) + innerAcronym);
                array = Array.newInstance(compClass, l);
            } catch (ClassNotFoundException e) {
                throw new RuntimeException(e);
            }
            for (int i = 0; i < l; i++) {
                Array.set(array, i,  deserializeArrayColumn(innerType, innerAcronym, dims-1) );
            }
            return array;
        }

        array = Array.newInstance(innerType, l);

        // deserialise actual content
        if (innerType.isPrimitive()) {
            if (innerType == Boolean.TYPE) {
                boolean[] a = (boolean[])array;
                for (int i = 0; i < l; i++) {
                    a[i] = in.readBoolean();
                }
            } else if (innerType == Byte.TYPE) {
                in.readFully((byte[])array);
            } else if (innerType == Character.TYPE) {
                char[] a = (char[])array;
                for (int i = 0; i < l; i++) {
                    a[i] = in.readChar();
                }
            } else if (innerType == Float.TYPE) {
                float[] a = (float[])array;
                for (int i = 0; i < l; i++) {
                    a[i] = in.readFloat();
                }
            } else if (innerType == Double.TYPE) {
                double[] a = (double[])array;
                for (int i = 0; i < l; i++) {
                    a[i] = in.readDouble();
                }
            } else if (innerType == Integer.TYPE) {
                int[] a = (int[])array;
                for (int i = 0; i < l; i++) {
                    a[i] = in.readInt();
                }
            } else if (innerType == Long.TYPE) {
                long[] a = (long[])array;
                for (int i = 0; i < l; i++) {
                    a[i] = in.readLong();
                }
            } else if (innerType == Short.TYPE) {
                short[] a = (short[])array;
                for (int i = 0; i < l; i++) {
                    a[i] = in.readShort();
                }
            } else {
                throw new UnsupportedOperationException(
                        "Unsupported type: " + innerType);
            }
            return array;
        }
        if (Object.class.isAssignableFrom(innerType)) {
            for (int i = 0; i < l; i++) {
                Array.set(array, i, deserializeObject());
            }
            return array;
        }
        throw new UnsupportedOperationException("Unsupported: " + innerType);
    }

    private final void deserializeDBHashtable(DBHashMap<Object, Object> c) {
        final int size = in.readInt();
        c.clear();
        c.resize(size);
        Object key = null;
        Object val = null;
        MapEntry[] values = new MapEntry[size];
        for (int i=0; i < size; i++) {
            //c.put(deserializeObject(), deserializeObject());
            //The following check is necessary where the content of the 
            //Collection contains restricted objects, in which case 'null'
            //is transferred.
            key = deserializeObject();
            val = deserializeObject();
            //We don't fill the Map here, because hashCodes rely on fully loaded objects.
            //c.put(key, val);
            values[i] = new MapEntry(key, val);
        }
        mapsToFill.add(new MapValuePair(c, values));
    }
    
    private final void deserializeDBLargeVector(DBLargeVector<Object> c) {
        final int size = in.readInt();
        c.clear();
        c.resize(size);
        Object val = null;
        for (int i=0; i < size; i++) {
            val = deserializeObject();
            if (val != null) {
                c.add(val);
            }
        }
    }

    private final void deserializeDBVector(DBArrayList<Object> c) {
        final int size = in.readInt();
        c.clear();
        c.resize(size);
        Object val = null;
        for (int i=0; i < size; i++) {
            val = deserializeObject();
            if (val != null) {
                c.add(val);
            }
        }
    }
    
    private final String deserializeString() {
    	return in.readString();
    }

    private final Class<?> readClassInfo() {
    	final byte id = in.readByte();
    	switch (id) {
    	//null-reference
    	case -1: return null;
    	case SerializerTools.REF_PERS_ID: {
    		long soid = in.readLong();
    		//Schema Evolution
    		//================
    		//Maybe we need to create an OID->Schema-OID index?
    		//Due to schema evolution, the Schema-OID in serialized references may be out-dated with
    		//respect to the referenced object. Generally, it may be impossible to create a hollow 
    		//object from the OID.
    		//Alternative: look up the schema and create a hollow of the latest version?!?!?     
    		//TODO ZooClassDef def = _cache.getSchemaLatestVersion(soid);
    		ZooClassDef def = cache.getSchema(soid);
    		if (def.getJavaClass() != null) {
    			return def.getJavaClass();
    		}
    		try {
    			return Class.forName(def.getClassName());
    		} catch (ClassNotFoundException e) {
    			throw new BinaryDataCorruptedException("Class not found: " + def.getClassName(), e);
    		}
    	}
    	case SerializerTools.REF_ARRAY_ID: {
    		//an array
    		return byte[].class;
    	}
    	case 0: {
    		//if id==0 read the class
    		String cName = deserializeString();
    		try {
    			Class<?> cls = Class.forName(cName);
    			usedClasses.add(cls);
    			return cls;
    		} catch (ClassNotFoundException e) {
    			if (cName.length() > 100) {
    				cName = cName.substring(0, 100);
    			}
    			//Do not embed 'e' to avoid problems with excessively long class names.
    			throw new BinaryDataCorruptedException(
    					"Class not found: \"" + cName + "\" (" + id + ")");
    		}
    	}
    	default: {
    		if (id < SerializerTools.REF_CLS_OFS) {
    			return SerializerTools.PRE_DEF_CLASSES_ARRAY.get(id);
    		} else {
    			return usedClasses.get(id - 1 - SerializerTools.REF_CLS_OFS);
    		}	
    	}
    	}
//        throw new DataStreamCorruptedException("ID (max=" + usedClasses.size() + "): " + id);

//        if (id == -1) {
//            //null-reference
//            return null;
//        }
//        if (id == SerializerTools.REF_PERS_ID) {
//        	long soid = _in.readLong();
//        	//Schema Evolution
//        	//================
//        	//Maybe we need to create an OID->Schema-OID index?
//        	//Due to schema evolution, the Schema-OID in serialized references may be out-dated with
//        	//respect to the referenced object. Generally, it may be impossible to create a hollow 
//        	//object from the OID.
//        	//Alternative: look up the schema and create a hollow of the latest version?!?!?     
//        	//TODO ZooClassDef def = _cache.getSchemaLatestVersion(soid);
//        	ZooClassDef def = _cache.getSchema(soid);
//        	if (def.getJavaClass() != null) {
//        		return def.getJavaClass();
//        	}
//        	try {
//				return Class.forName(def.getClassName());
//			} catch (ClassNotFoundException e) {
//				throw new DataStreamCorruptedException("Class not found: " + def.getClassName(), e);
//			}
//        }
//        if (id > 0 && id < SerializerTools.REF_CLS_OFS) {
//            return SerializerTools.PRE_DEF_CLASSES_ARRAY.get(id);
//        }
//        if (id > 0) {
//            return _usedClasses.get(id - 1 - SerializerTools.REF_CLS_OFS);
//        }
//        
//        if (id == 0) {
//            //if id==0 read the class
//            String cName = deserializeString();
//            try {
//                Class<?> cls = Class.forName(cName);
//                _usedClasses.add(cls);
//                return cls;
//            } catch (ClassNotFoundException e) {
//                throw new DataStreamCorruptedException(
//                        "Class not found: \"" + cName + "\" (" + id + ")", e);
//            }
//        }
    }
    
    private final Object createInstance(Class<?> cls) {
        try {
            //find the constructor
            Constructor<?> c = DEFAULT_CONSTRUCTORS.get(cls);
            if (c == null) {
                //TODO remove special treatment. Allow Serializable / Externalizable? Via Properties?
                if (File.class.isAssignableFrom(cls)) {
                    return new File("");
//                } else if (ZooFieldDef.class.isAssignableFrom(cls)) {
//                	return new ZooFieldDef(in);
                }
                c = cls.getDeclaredConstructor((Class[])null);
                c.setAccessible(true);
                DEFAULT_CONSTRUCTORS.put(cls, c);
            }
            //use the constructor
            return c.newInstance();
        } catch (SecurityException e1) {
            throw new RuntimeException(e1);
        } catch (NoSuchMethodException e1) {
        	//it is possible that 'cls' is an inner-class
        	return createInstanceInnerClass(cls);
            
        } catch (IllegalArgumentException e) {
            throw new RuntimeException(e);
        } catch (InstantiationException e) {
            throw new RuntimeException(e);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        } catch (InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }
    



	//TODO rename to setOid/setPersistentState
    //TODO merge with createdumy & createObject
    final void prepareObject(ZooPCImpl obj, long oid, boolean hollow, 
    		ZooClassDef classDef) {
//        obj.jdoNewInstance(sm); //?
        
        if (hollow) {
        	cache.addToCache(obj, classDef, oid, ObjectState.HOLLOW_PERSISTENT_NONTRANSACTIONAL);
        } else {
        	cache.addToCache(obj, classDef, oid, ObjectState.PERSISTENT_CLEAN);
        }
    }
    
    private static final boolean isPersistentCapableClass(Class<?> cls) {
        return ZooPCImpl.class.isAssignableFrom(cls);
    }
    
    private final ZooPCImpl hollowForOid(long oid, Class<?> cls) {
        if (oid == 0) {
            throw new IllegalArgumentException();
        }
        
        //check cache
    	ZooPCImpl obj = cache.findCoByOID(oid);
        if (obj != null) {
        	//Object exist.
            return obj;
        }
        
        ZooClassDef clsDef = cache.getSchema(cls, node);
        obj = (ZooPCImpl) createInstance(cls);
        prepareObject(obj, oid, true, clsDef);
        return obj;
    }
    
    /**
     * Creates an instance of an inner class 'cls'
     * According to the API doc and language specification 
     * (http://docs.oracle.com/javase/7/docs/api/java/lang/reflect/Constructor.html
     *  and
     *  http://docs.oracle.com/javase/specs/jls/se7/html/jls-15.html#jls-15.9.3)
     * we need to instantiate first its parent-class and hand over this parent-instance to the 
     * constructor
     * 
     * @param cls
     * @return
     */
    private Object createInstanceInnerClass(Class<?> cls) {
    	try {
	    	Constructor<?>[] innerConstructors = cls.getDeclaredConstructors();
	    	if (innerConstructors.length > 0) {
	    		
	    		Class<?> outerClass = innerConstructors[0].getParameterTypes()[0];
	    		Constructor<?> outerConstructor = outerClass.getDeclaredConstructor((Class[]) null);
	    		outerConstructor.setAccessible(true);
	    		Object o = outerConstructor.newInstance();
	
				return innerConstructors[0].newInstance(o);
	    	}
    	} catch (Exception e) {
			e.printStackTrace();
			throw new RuntimeException("Class requires default constructor (can be private): " + cls.getName(), e);
		}
		return null;
    	
  	}
    
    /**
     * Updates the statistics: size of attribute, if field is non null
     * Updates the statistics: size of class with bytes, if field is null
     * @param obj
     * @param bytesRead
     * @param field
     */
    private void reportFieldSizeRead(Object obj, long bytesRead, Field field) {
    	if (DBStatistics.isEnabled() && obj.getClass() != ZooClassDef.class) {
    		if (field != null) {
    			if (bytesRead >= ProfilingConfig.LOB_TRESHOLD) {
    	    		ProfilingManager.getInstance().getFieldManager().updateLobCandidates(obj.getClass(), field);
    	    	}
    	    	ProfilingManager.getInstance().getClassSizeManager().getClassStats(obj.getClass()).updateField(field.getName(), bytesRead);
    		} else {
    			//update class statistics with size of class
    			ProfilingManager.getInstance().getClassSizeManager().getClassStats(obj.getClass()).updateClass(bytesRead);
    		}
    	}
    }
}
