package org.zoodb.profiling.api.impl;

import java.util.LinkedList;
import java.util.List;

import org.zoodb.profiling.api.Activation;
import org.zoodb.profiling.api.IPath;
import org.zoodb.profiling.api.IPathManager;
import org.zoodb.profiling.api.IPathTreeNode;
import org.zoodb.profiling.api.ITreeTraverser;

/**
 * @author tobiasg
 *
 */
public class PathManagerTree implements IPathManager {
	
	private List<PathTree> pathTrees;
	private List<PathTree> classLevelPathTrees;
	
	public PathManagerTree() {
		pathTrees = new LinkedList<PathTree>();
	}
	
	@Override
	public void addActivationPathNode(Activation a, Object predecessor) {
		/**
		 * predecessor == null indicates that object was returned by a query
		 */
		if (predecessor == null) {
			IPathTreeNode fatherNode = findNode(a.getActivator().getClass().getName(),a.getOid());
			
			if (fatherNode == null) {
				PathTreeNode rootNode = new PathTreeNode(a);
				rootNode.setClazz(a.getActivator().getClass().getName());
				rootNode.setOid(a.getOid());
				rootNode.setTriggerName("_query");
				
				PathTreeNode rootChildren = new PathTreeNode(a);
				try {
					rootChildren.setClazz(a.getMemberResult().getClass().getName());
					rootChildren.setOid(a.getTargetOid());
					rootChildren.setTriggerName(a.getMemberName());

					rootNode.addChildren(rootChildren);
					PathTree pt = new PathTree(rootNode);
					pathTrees.add(pt);
				} catch(Exception e) {
					
				}
			} else {
				/**
				 * Traversal of another branch of same node.
				 */
				PathTreeNode rootChildren = new PathTreeNode(a);
				
				try {
					rootChildren.setClazz(a.getMemberResult().getClass().getName());
					rootChildren.setOid(a.getTargetOid());
					rootChildren.setTriggerName(a.getMemberName());

					fatherNode.addChildren(rootChildren);
					
				} catch(Exception e) {
					
				}

			}
			
		} else {
			IPathTreeNode fatherNode = findNode(a.getActivator().getClass().getName(),a.getOid());
			
			//collection fix
			if (a.getMemberResult() != null) {
				PathTreeNode newChild = new PathTreeNode(a);
				newChild.setClazz(a.getMemberResult().getClass().getName());
				newChild.setOid(a.getTargetOid());
				newChild.setTriggerName(a.getMemberName());
				fatherNode.addChildren(newChild);

			}
		}
	}
	
	
	/**
	 * @param clazzName
	 * @param oid
	 * @return The first in any tree that matches (clazzName,oid)
	 */
	private IPathTreeNode findNode(String clazzName, String oid) {
		IPathTreeNode fatherNode = null;
		for (PathTree pt : pathTrees) {
			fatherNode= pt.getNode(clazzName,oid);
			if (fatherNode != null) {
				break;
			}
		}
		return fatherNode;
	}
	

	@Override
	public List<IPath> getPaths() {
		// TODO: implement behaviour for non-list shaped paths
		for (PathTree pt: pathTrees) {
			if (pt.isList()) {
				prettyPrintPath(pt.getActivatorClasses());
			}
		}
		
		return null;
	}

	@Override
	public void prettyPrintPaths() {
		for (PathTree pt : pathTrees) {
			System.out.println("Starting new path tree...");
			
			pt.prettyPrint();
		}

	}
	
	
	public void prettyPrintClassPaths() {
		for (PathTree pt : classLevelPathTrees) {
			System.out.println("Starting new path tree...");
			
			pt.prettyPrint();
		}

	}
	
	public void prettyPrintWithTrigger() {
		for (PathTree pt : pathTrees) {
			System.out.println("Starting new path tree...");
			
			pt.prettyPrintWithTrigger();
		}

	}
	
	private void prettyPrintPath(List<Class> activatorClasses) {
		int indent=0;
		for (Class clazz: activatorClasses) {
			for (int i=0;i<indent;i++) {
				System.out.print("\t");
			}
			indent++;
			System.out.print("-->" + clazz.getName());
			System.out.println();
			
		}
	}
	
	@Override
	public void aggregateObjectPaths() {
		classLevelPathTrees = new LinkedList<PathTree>();
		
		int pathTreeCount = pathTrees.size();
		
		classLevelPathTrees.add(pathTrees.get(0));
		
		for (int i=1;i<pathTreeCount;i++) {
			overlayPathTree(pathTrees.get(i));
		}
	}

	/**
	 * @param pathTree will be integrated in the already existing class-level path trees.
	 * For each node in the pathTree, find it in one of the already existing class-level trees and insert its children nodes (children nodes only!)
	 */
	private void overlayPathTree(PathTree pathTree) {
		ITreeTraverser traverser = new TreeTraverser(pathTree);
		IPathTreeNode currentNode = null;
		
		while ( (currentNode = traverser.next()) != null) {
			IPathTreeNode matchedNode = null;
			for (PathTree clpt : classLevelPathTrees) {
				matchedNode = clpt.getPathNodeClass(currentNode);
				
				if (matchedNode != null) {
					matchedNode.incAccessFrequency();
			
					break;
				}
			}
		}
	}
	
	

}