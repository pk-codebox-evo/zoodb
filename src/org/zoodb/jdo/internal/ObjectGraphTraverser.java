package org.zoodb.jdo.internal;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import javax.jdo.PersistenceManager;

import org.zoodb.jdo.api.DBCollection;
import org.zoodb.jdo.api.DBHashtable;
import org.zoodb.jdo.api.DBLargeVector;
import org.zoodb.jdo.api.DBVector;
import org.zoodb.jdo.internal.client.CachedObject;
import org.zoodb.jdo.internal.client.session.ClientSessionCache;
import org.zoodb.jdo.spi.PersistenceCapableImpl;
import org.zoodb.jdo.stuff.DatabaseLogger;

/**
 * This class traverses all objects in the Java cache. It looks for new 
 * objects that have not been made persistent and makes them persistent.
 * <p>
 * This class is only public so it can be accessed by the test harness. 
 * Please do not use.
 * <p>
 * WARNING: In this context, ObjectIdentityHashsets should be used with care.
 * Since this kind of Set uses the hashcode of an object, it is likely to
 * load the object into the client, even when calling contains(). Using such
 * a Set in context of the cache may result in loading hollow objects as well.
 * @author Tilmann Zaeschke
 */
public class ObjectGraphTraverser {

    private static final Logger _LOGGER = 
        Logger.getLogger(ObjectGraphTraverser.class.getPackage().getName());

    private final PersistenceManager _pm;

    //TODO use ZooClassDef from cached object instead? AVoids 'static' modifier!
    private static final IdentityHashMap<Class<? extends Object>, Field[]> _seenClasses = 
        new IdentityHashMap<Class<? extends Object>, Field[]>();

    private final ObjectIdentitySet<Object> _seenObjects;
    private final ArrayList<Object> _workList;

    /**
     * This HashSet contains types that are not persistent and that
     * cannot refer to other objects/classes (as a containers can do).
     */
    private final static ObjectIdentitySet<Class<?>> SIMPLE_TYPES;
    static {
        SIMPLE_TYPES = new ObjectIdentitySet<Class<?>>();
        SIMPLE_TYPES.add(Boolean.class);
        SIMPLE_TYPES.add(Byte.class);
        SIMPLE_TYPES.add(Character.class);
        SIMPLE_TYPES.add(Double.class);
        SIMPLE_TYPES.add(Float.class);
        SIMPLE_TYPES.add(Integer.class);
        SIMPLE_TYPES.add(Long.class);
        SIMPLE_TYPES.add(Short.class);
        SIMPLE_TYPES.add(Boolean.TYPE);
        SIMPLE_TYPES.add(Byte.TYPE);
        SIMPLE_TYPES.add(Character.TYPE);
        SIMPLE_TYPES.add(Double.TYPE);
        SIMPLE_TYPES.add(Float.TYPE);
        SIMPLE_TYPES.add(Integer.TYPE);
        SIMPLE_TYPES.add(Long.TYPE);
        SIMPLE_TYPES.add(Short.TYPE);
        SIMPLE_TYPES.add(BigDecimal.class);
        SIMPLE_TYPES.add(BigInteger.class);
        SIMPLE_TYPES.add(String.class);
        //more?

        //TODO Class should NOT be used as persistent attribute 
        //(Schema evolution is not??)
        SIMPLE_TYPES.add(Class.class);


        //TODO use PERSISTENT_CONTAINER_TYPES
    }
    
    private long _madePersistent = 0;

    /**
     * This class is only public so it can be accessed by the test harness. 
     * Please do not use.
     * @param pm 
     */
    public ObjectGraphTraverser(PersistenceManager pm, ClientSessionCache cache) {
        _pm = pm;
        
        //We need to copy the cache to a local list, because the cache we might make additional
        //objects persistent while iterating. We need to ensure that these new objects are covered
        //as well. And we have the problem of concurrent updates in the cache.
        Collection <CachedObject> cObjs = cache.getAllObjects();
        //TODO use bucket array List? Last time I tested this it was two times slower!!!
        _workList = new ArrayList<Object>(cObjs.size());
        for (CachedObject co: cObjs) {
        	Object o = co.obj;
        	//ignore clean objects? Ignore hollow objects?
        	if (co.isDirty()) {
        		_workList.add(o);
        	}
        }

        _seenObjects = new ObjectIdentitySet<Object>(_workList.size()*2);
    }

    /**
     * This class is only public so it can be accessed by the test harness. 
     * Please do not use.
     */
    public synchronized final void traverse() {
        //Intention is to find the NEW objects that will become persistent
        //through reachability.
        //For this, we have to check objects that are DIRTY or NEW (by 
        //makePersistent()). 

    	DatabaseLogger.debugPrintln(1, "Starting OGT: " + _workList.size());
        long t1 = System.currentTimeMillis();
        long nObjects = 0;
        while (!_workList.isEmpty()) {
            nObjects++;
            Object object = _workList.remove(_workList.size()-1);
            //Objects in the work-list are always already made persistent:
            //Objects in the work-list are either added by the constructor 
            //(already made  persistent).
            //or have been added by addToWorkList (which uses 
            //makePersistent() first).

            if (object instanceof DBCollection) {
                doPersistentContainer(object);
            } else if (object instanceof Object[]) {
                doArray((Object[]) object);
            } else if (object instanceof Collection) {
                doCollection((Collection) object);
            } else if (object instanceof Map) {
                doCollection(((Map) object).keySet());
                doCollection(((Map) object).values());
            } else if (object instanceof Dictionary) {
                doEnumeration(((Dictionary) object).keys());
                doEnumeration(((Dictionary) object).elements());
            } else if (object instanceof Enumeration) {
                doEnumeration((Enumeration) object);
            } else {
                doObject(object);
            }
        }
        long t2 = System.currentTimeMillis();
        DatabaseLogger.debugPrintln(1, "Finished OGT: " + nObjects + " (seen="
                + _seenObjects.size() + " ) / " + (t2-t1)/1000.0
                + " MP=" + _madePersistent);    
    }

    final private void addToWorkList(Object object) {
        if (object == null)
            return;

        Class<? extends Object> cls = object.getClass();
        if (SIMPLE_TYPES.contains(cls)) {
        	//TODO FIX? zoodb
            //This can happen when called from doMap(), doContainer(), ...
            return;
        }

        if (object instanceof PersistenceCapableImpl) {
        	PersistenceCapableImpl pc = (PersistenceCapableImpl) object;
        	//This can happen if e.g. a LinkedList contains new persistent capable objects.
            if (!pc.jdoIsPersistent()) {
                //Make object persistent, if necessary
                _pm.makePersistent(object);
                _madePersistent++;
            } else {
            	//This object is already persistent. It is either in the worklist or it is 
            	//uninteresting (not dirty).
            	return;
            }
        }

        if (!_seenObjects.contains(object)) {
            _workList.add(object);
            _seenObjects.add(object);
        }
    }

    final private void doArray(Object[] array) {
        for (Object o: array) {
            addToWorkList(o);
        }
    }

    final private void doEnumeration(Enumeration enumeration) {
        while (enumeration.hasMoreElements()) {
            addToWorkList(enumeration.nextElement());
        }
    }

    final private void doCollection(Collection<?> col) {
        for (Object o: col) {
            addToWorkList(o);
        }
    }

    private final void doObject(Object parent) {			
        for (Field field: getFields(parent.getClass())) {
            try {
                //add the value to the working list
                addToWorkList(field.get(parent));
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(
                        "Unable to access field \"" + field.getName()
                        + "\" of class \"" + parent.getClass().getName()
                        + "\" from OGT.", e);
            }
        }
    }

    /** 
     * Handles persistent Collection classes.
     */ 
    private final void doPersistentContainer(Object container) {
        if (container instanceof DBVector) {
            doCollection((DBVector)container);
        } else if (container instanceof DBLargeVector) {
//            doEnumeration(((DBLargeVector)container).elements(), container);
            doCollection(((DBLargeVector)container));
        } else if (container instanceof DBHashtable) {
            DBHashtable t = (DBHashtable)container;
            doCollection(t.keySet());
            doCollection(t.values());
        } else {
            _LOGGER.severe("WARNING: Objects of this type cannot be " +
                        "distributed or propagated: " + container.getClass());
            throw new IllegalArgumentException(
                    "Unrecognized persistent container in OGT: "
                    + container.getClass());
        }
    }

    private static final boolean isSimpleType(Field field) {
        int mod = field.getModifiers();
        if (Modifier.isStatic(mod) || Modifier.isTransient(mod)) {
            return true;
        }

        Class<?> cls = field.getType();
        while (cls.isArray()) {
        	cls = cls.getComponentType();
        }
        return SIMPLE_TYPES.contains(cls);

    }

    /**
     * Returns a List containing all of the Field objects for the given class.
     * The fields include all public and private fields from the given class 
     * and its super classes.
     *
     * @param c Class object
     * @return Returns list of interesting fields
     */
    private static final Field[] getFields (Class<? extends Object> cls) {
        if (_seenClasses.containsKey(cls)) {
            return _seenClasses.get(cls);
        }

        if (cls == Class.class) {
            //Fields of type Class can not be allowed! -> schema evolution!
            throw new IllegalArgumentException("Encountered object of typ 'Class'");
        }

        List<Field> retL = new ArrayList<Field>();
        for (Field f: cls.getDeclaredFields ()) {
        	if (!isSimpleType(f)) {
        		retL.add(f);
        		f.setAccessible(true);
        	}
        }

        if (cls.getSuperclass() != Object.class) {
        	for (Field f: getFields(cls.getSuperclass())) {
        		retL.add(f);
        	}
        }
        Field[] ret = retL.toArray(new Field[retL.size()]);
        _seenClasses.put(cls, ret);
        return ret;
    }
}