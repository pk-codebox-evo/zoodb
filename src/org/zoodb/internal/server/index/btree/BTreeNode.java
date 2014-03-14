package org.zoodb.internal.server.index.btree;

import org.zoodb.internal.util.Pair;

import java.util.Arrays;

/**
 * Represents the node of a B+ tree.
 *
 * Support for linked-lists of nodes on the leaf level is yet to be added.
 */
public class BTreeNode {

    private final boolean isLeaf;
    private final int order;

    //ToDo maybe we want to have the keys set dynamically sized somehow
    private int numKeys;
    private long[] keys;

    private long[] values;
    private BTreeNode[] children;
    private BTreeNode parent;

    public BTreeNode(BTreeNode parent, int order, boolean isLeaf) {
        this.parent = parent;
        this.order = order;
        this.isLeaf = isLeaf;

        keys = new long[order - 1];
        numKeys = 0;

        if (isLeaf) {
            values = new long[order - 1];
        } else {
            children = new BTreeNode[order];
        }
    }

    /**
     * Returns the index + 1 of the key received as an argument. If the key is not in the array, it will return
     * the index of the smallest key in the array that is larger than the key received as argument.
     *
     * @param key
     * @return
     */
    public int findKeyPos(long key) {
        //Todo make method package and add test to the same package
        if (numKeys == 0) {
            return 0;
        }
        int low = 0;
        int high = numKeys - 1;
        int mid = 0;
        boolean found = false;
        //perform binary search
        while (!found && low <= high) {
            mid = low + (high - low) / 2;
            if (keys[mid] == key) {
                found = true;
            } else {
                if (key < keys[mid]) {
                    high = mid - 1;
                } else {
                    low = mid + 1;
                }
            }
        }

        //if the key is not here, find the child subtree that has it
        if (!found) {
            if (mid == 0 && key < keys[0]) {
                return 0;
            } else if (key < keys[mid]) {
                return mid;
            }
        }
        return mid + 1;
    }

    /**
     * Find the value corresponding to a key in a leaf node.
     *
     * @param key   The key received as argument
     * @return      The value corresponding to the key in the index.
     *              If the key is not found in the index, -1 is returned.
     */
    public long findValue(long key) {
        if (!this.isLeaf()) {
            throw new UnsupportedOperationException("Should only be called on leaf nodes.");
        }
        if (numKeys == 0) {
            return 0;
        }
        int low = 0;
        int high = numKeys - 1;
        int mid = 0;
        boolean found = false;
        while (!found && low <= high) {
            mid = low + (high - low) / 2;
            if (keys[mid] == key) {
                found = true;
            } else {
                if (key < keys[mid]) {
                    high = mid - 1;
                } else {
                    low = mid + 1;
                }
            }
        }
        if (!found) {
            return -1;
        }
        return values[mid];
    }

    public BTreeNode findChild(long key) {
        return children[findKeyPos(key)];
    }

    /**
     * Leaf put.
     *
     * Requires that node is not full.
     * @param key
     * @param value
     */
    public void put(long key, long value) {
        if (!isLeaf()) {
            throw new UnsupportedOperationException("Should only be called on leaf nodes.");
        }

        int pos = findKeyPos(key);
        System.arraycopy(keys, pos, keys, pos + 1, numKeys - pos);
        System.arraycopy(values, pos, values, pos + 1, numKeys - pos);
        keys[pos] = key;
        values[pos] = value;
        numKeys++;
    }

    /**
     * Inner-node put. Places key to the left of the next bigger key k'.
     *
     * Requires that 
     * 		key <= keys(newNode)
     * 		all elements of the left child of k' are  smaller than key 
     * 		node is not full.
     * Assumes that leftOf(key') <= keys(newNode) 
     * @param key
     * @param newNode
     */
    public void put(long key, BTreeNode newNode) {
        if (isLeaf()) {
            throw new UnsupportedOperationException("Should only be called on inner nodes.");
        } else if(numKeys == 0) {
            throw new UnsupportedOperationException("Should only be called when node is non-empty.");
        }

        int pos = findKeyPos(key);
        System.arraycopy(children, pos + 1, children, pos + 2, numKeys - pos);
        children[pos+1] = newNode;
        newNode.setParent(this);

        System.arraycopy(keys, pos, keys, pos + 1, numKeys - pos);
        keys[pos] = key;
        numKeys++;
    }


    /**
     * Root-node put.
     *
     * Used when a non-leaf root is empty and will be populated by a single key and two nodes.
     * @param key       The new key on the root.
     * @param left      The left node.
     * @param right     The right node.
     */
    public void put(long key, BTreeNode left, BTreeNode right) {
        if (!isRoot()) {
            throw new UnsupportedOperationException("Should only be called on the root node.");
        }

        keys[0] = key;
        numKeys = 1;

        children[0] = left;
        children[1] = right;
        
        left.setParent(this);
        right.setParent(this);
    }

    /**
     * Current only works for leaves.
     * @return
     */
    public BTreeNode split() {
        if (!isLeaf()) {
            throw new UnsupportedOperationException("Should only be called on leaf nodes.");
        }

        BTreeNode rightNode = new BTreeNode(parent, order, true);
        int keysInLeftNode = (int) Math.ceil((order-1) / 2.0);
        int keysInRightNode = order - 1 - keysInLeftNode;
        System.arraycopy(keys, keysInLeftNode, rightNode.getKeys(), 0, keysInRightNode);
        System.arraycopy(values, keysInLeftNode, rightNode.getValues(), 0, keysInRightNode);

        numKeys = keysInLeftNode;
        rightNode.setNumKeys(keysInRightNode);
        rightNode.setParent(parent);

        return rightNode;
    }

    /**
     * Corrected version of the leaf split method. This method should take into account the
     * key to be added when deciding the number of keys to split on.
     * @param newKey
     * @return
     */
    public BTreeNode split(long newKey) {
        if (!isLeaf()) {
            throw new UnsupportedOperationException("Should only be called on leaf nodes.");
        }
        BTreeNode rightNode = new BTreeNode(parent, order, true);
        int keysInLeftNode = (int) Math.ceil((order-1) / 2.0);
        int keysInRightNode = order - 1 - keysInLeftNode;

        //check if the new key would be inserted in the left node after equal split
        if (newKey < keys[keysInLeftNode - 1]) {
            if (keysInLeftNode + 1 - keysInRightNode > 1) {
                //correct sizes if needed
                keysInLeftNode--;
                keysInRightNode++;
            }
        }
        System.arraycopy(keys, keysInLeftNode, rightNode.getKeys(), 0, keysInRightNode);
        System.arraycopy(values, keysInLeftNode, rightNode.getValues(), 0, keysInRightNode);

        numKeys = keysInLeftNode;
        rightNode.setNumKeys(keysInRightNode);
        rightNode.setParent(parent);

        return rightNode;
    }

    /**
     * Inserts a key and a new node to the inner structure of the tree.
     *
     * This methods is different from the split() method because when keys are insert on inner node,
     * the children pointers should also be shifted accordingly.
     *
     * @param key
     * @param newNode
     * @return
     */
    public Pair<BTreeNode, Long> putAndSplit(long key, BTreeNode newNode) {
        if (isLeaf()) {
            throw new UnsupportedOperationException("Should only be called on inner nodes.");
        }

        //create a temporary node to allow the insertion
        BTreeNode tempNode = new BTreeNode(null, order + 1, false);
        System.arraycopy(keys, 0, tempNode.getKeys(), 0, numKeys);
        System.arraycopy(children, 0, tempNode.getChildren(), 0, order);
        tempNode.setNumKeys(numKeys);
        tempNode.put(key, newNode);

        //split
        BTreeNode right = new BTreeNode(parent, order, false);
        int keysInLeftNode = (int) Math.floor(order / 2.0);
        //populate left node
        System.arraycopy(tempNode.getKeys(), 0, keys, 0, keysInLeftNode);
        System.arraycopy(tempNode.getChildren(), 0, children, 0, keysInLeftNode + 1);
        numKeys = keysInLeftNode;

        //populate right node
        int keysInRightNode = order-keysInLeftNode-1;
        System.arraycopy(tempNode.getKeys(), keysInLeftNode + 1, right.getKeys(), 0, keysInRightNode);
        System.arraycopy(tempNode.getChildren(), keysInLeftNode + 1, right.getChildren(), 0, keysInRightNode+1);
        right.setNumKeys(keysInRightNode);

        long keyToMoveUp = tempNode.getKeys()[keysInLeftNode];

        //update children pointers
        newNode.setParent(key < keyToMoveUp ? this : right);
        for (int i = keysInLeftNode + 1; i < order + 1; i++) {
            tempNode.getChildren()[i].setParent(right);
        }
        right.setParent(parent);

        return new Pair<>(right, keyToMoveUp);
    }



    public boolean isLeaf() {
        return isLeaf;
    }

    public boolean isRoot() {
        return parent == null;
    }

    public int getNumKeys() {
        return numKeys;
    }

    public BTreeNode getParent() {
        return parent;
    }

    public long[] getKeys() {
        return keys;
    }
    
    public long getSmallestKey() {
    	return keys[0];
    }

    public long[] getValues() {
        return values;
    }

    public void setNumKeys(int numKeys) {
        this.numKeys = numKeys;
    }

    public long smallestKey() {
        return keys[0];
    }

    public long largestKey() {
        return keys[numKeys - 1];
    }

    public void setParent(BTreeNode parent) {
        this.parent = parent;
    }

    public BTreeNode[] getChildren() {
        return children;
    }

    public void setChildren(BTreeNode[] children) {
        this.children = children;
    }

    public void setKeys(long[] keys) {
        this.keys = keys;
    }

    public void setValues(long[] values) {
        this.values = values;
    }

    public String toString() {
    	String ret = (isLeaf() ? "leaf" : "inner") + "-node: k:";
    	ret += "[";
    	for (int i=0; i < this.getNumKeys(); i++) {
    		ret += Long.toString(keys[i]);
    		if(i!=this.getNumKeys()-1)
    			ret+= " ";
    	}
    	ret+= "]";
    	if(isLeaf()) {
    		ret += ",   \tv:"; 
	    	ret += "[";
	    	for (int i=0; i < this.getNumKeys(); i++) {
	    		ret += Long.toString(values[i]);
	    		if(i!=this.getNumKeys()-1)
	    			ret+= " ";
	    	}
	    	ret += "]";
    	} else {
    		ret+= "\n\tc:"; 
	    	for (int i=0; i < this.getNumKeys()+1; i++) {
				String[] lines = children[i].toString().split("\r\n|\r|\n");
			for(String l : lines) {
					ret+="\n\t" + l;
				}
    		}
    	}
    	return ret;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BTreeNode)) return false;

        BTreeNode bTreeNode = (BTreeNode) o;
        
        if (isLeaf != bTreeNode.isLeaf) return false;
        if (numKeys != bTreeNode.numKeys) return false;
        if (order != bTreeNode.order) return false;
        if (!arrayEquals(children, bTreeNode.children, numKeys + 1)) return false;
        if (!arrayEquals(keys, bTreeNode.keys, numKeys)) return false;
        // checking for parent equality would result in infinite loop
        // if (parent != null ? !parent.equals(bTreeNode.parent) : bTreeNode.parent != null) return false;
        if (!arrayEquals(values, bTreeNode.values, numKeys)) return false;

        return true;
    }

    private <T> boolean arrayEquals(T[] first, T[] second, int size) {
        if (first == second) {
            return true;
        }
        if (first == null || second == null) {
            return false;
        }
        if (first.length < size || second.length < size) {
            return false;
        }

        for (int i = 0; i < size; i++) {
            if ( (first[i] != second[i] ) && (first[i] != null ) && (!first[i].equals(second[i]))) {
                return false;
            }
        }
        return true;
    }

    private boolean arrayEquals(long[] first, long[] second, int size) {
        if (first == second) {
            return true;
        }
        if (first == null || second == null) {
            return false;
        }
        if (first.length < size || second.length < size) {
            return false;
        }

        for (int i = 0; i < size; i++) {
            if (!(first[i] == second[i])) {
                return false;
            }
        }
        return true;
    }
}
