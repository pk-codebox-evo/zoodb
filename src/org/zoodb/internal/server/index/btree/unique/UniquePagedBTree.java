package org.zoodb.internal.server.index.btree.unique;

import org.zoodb.internal.server.index.btree.BTreeBufferManager;
import org.zoodb.internal.server.index.btree.PagedBTree;
import org.zoodb.internal.server.index.btree.PagedBTreeNode;
import org.zoodb.internal.util.Pair;

import java.util.NoSuchElementException;

/**
 * Abstracts the need to specify a BTreeNodeFactory, which is specific to this type of tree.
 *
 * Also, adds the buffer manager that will be used by this type of node as an argument.
 */
public class UniquePagedBTree extends PagedBTree<UniquePagedBTreeNode> {
	
    private static final int NO_VALUE = 0;

    public UniquePagedBTree(int pageSize, BTreeBufferManager bufferManager) {
        super(pageSize, bufferManager);
    }

    @Override
    public boolean isUnique() {
        return true;
    }

    /**
     * Retrieve the value corresponding to the key from the B+ tree.
     *
     * @param key
     * @return corresponding value or null if key not found
     */
    public Long search(long key) {
        if(isEmpty()) {
        	return null;
        }
        PagedBTreeNode current = root;

        while (!current.isLeaf()) {
            current = current.findChild(key, NO_VALUE);
        }

        try {
        	long value = findValue(current, key);
        	return value;
        } catch(NoSuchElementException e) {
            return null; 
        }
    }

    /**
     * Delete the value corresponding to the key from the tree.
     *
     * Deletion steps are as a follows:
     *  - find the leaf node that contains the key that needs to be deleted.
     *  - delete the entry from the leaf
     *  - at this point, it is possible that the leaf is underfull.
     *    In this case, one of the following things are done:
     *    - if the left sibling has extra keys (more than the minimum number), borrow keys from the left node
     *    - if that is not possible, try to borrow extra keys from the right sibling
     *    - if that is not possible, either both the left and right nodes have precisely half the max number of keys.
     *      The current node has half the max number of keys - 1 so a merge can be done with either of them.
     *      The left node is check for merge first, then the right one.
     *
     * @param key               The key to be deleted.
     */
	public long delete(long key) {
		return deleteEntry(key, NO_VALUE);
	}

    private long findValue(PagedBTreeNode node, long key) {
        if (!node.isLeaf()) {
            throw new IllegalStateException(
                    "Should only be called on leaf nodes.");
        }
        if (node.getNumKeys() > 0) {
            Pair<Boolean, Integer> result = node.binarySearch(key, NO_VALUE);
            int position = result.getB();
            boolean found = result.getA();
            if(found) {
            	return node.getValue(position);
            }
        }

        throw new NoSuchElementException("Key not found: " + key);
    }
}