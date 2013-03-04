/*
 * fb-contrib - Auxiliary detectors for Java programs
 * Copyright (C) 2005-2013 Dave Brosius
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */
package com.mebigfatguy.fbcontrib.detect;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.bcel.classfile.Code;
import org.apache.bcel.generic.Type;

import edu.umd.cs.findbugs.BugInstance;
import edu.umd.cs.findbugs.BugReporter;
import edu.umd.cs.findbugs.BytecodeScanningDetector;
import edu.umd.cs.findbugs.OpcodeStack;
import edu.umd.cs.findbugs.ba.ClassContext;

/**
 * finds methods that create and populate collections, and while knowing
 * the end size of those collections, does not pre allocate the collection
 * to be big enough. This just causes unneeded reallocations putting strain
 * on the garbage collector. */
public class PresizeCollections extends BytecodeScanningDetector {

    private static final Set<String> PRESIZEABLE_COLLECTIONS = new HashSet<String>();
    static {
        PRESIZEABLE_COLLECTIONS.add("java/util/ArrayBlockingQueue");
        PRESIZEABLE_COLLECTIONS.add("java/util/ArrayDeque");
        PRESIZEABLE_COLLECTIONS.add("java/util/ArrayList");
        PRESIZEABLE_COLLECTIONS.add("java/util/HashSet");
        PRESIZEABLE_COLLECTIONS.add("java/util/LinkedBlockingQueue");
        PRESIZEABLE_COLLECTIONS.add("java/util/LinkedHashSet");
        PRESIZEABLE_COLLECTIONS.add("java/util/PriorityBlockingQueue");
        PRESIZEABLE_COLLECTIONS.add("java/util/PriorityQueue");
        PRESIZEABLE_COLLECTIONS.add("java/util/TreeSet");
        PRESIZEABLE_COLLECTIONS.add("java/util/Vector");
    }

    private BugReporter bugReporter;
    private OpcodeStack stack;
    private int allocNumber;
    private Map<Integer, List<Integer>> allocToAddPCs;

    public PresizeCollections(BugReporter bugReporter) {
        this.bugReporter = bugReporter;
    }

    /**
     * overrides the visitor to initialize the opcode stack
     *
     * @param classContext the context object that holds the JavaClass being parsed
     */
    public void visitClassContext(ClassContext classContext) {
        try {
            stack = new OpcodeStack();
            allocToAddPCs = new HashMap<Integer, List<Integer>>();
            super.visitClassContext(classContext);
        } finally {
            stack = null;
            allocToAddPCs = null;
        }
    }

    /**
     * implements the visitor to reset the opcode stack
     * @param obj the context object of the currently parsed code block
     */
    @Override
    public void visitCode(Code obj) {
        stack.resetForMethodEntry(this);
        allocNumber = 0;
        allocToAddPCs.clear();
        super.visitCode(obj);

        for (List<Integer> pcs : allocToAddPCs.values()) {
            if (pcs.size() > 16) {
                bugReporter.reportBug(new BugInstance(this, "PSC_PRESIZE_COLLECTIONS", NORMAL_PRIORITY)
                .addClass(this)
                .addMethod(this)
                .addSourceLine(this, pcs.get(0)));
            }
        }
    }

    /**
     * implements the visitor to look for creation of collections
     * that are then populated with a known number of elements usually
     * based on another collection, but the new collection is not presized.
     * @param seen the opcode of the currently parsed instruction
     */
    @Override
    public void sawOpcode(int seen) {
        boolean sawAlloc = false;
        try {
            switch (seen) {
            case INVOKESPECIAL:
                String clsName = getClassConstantOperand();
                if (PRESIZEABLE_COLLECTIONS.contains(clsName)) {
                    String methodName = getNameConstantOperand();
                    if ("<init>".equals(methodName)) {
                        String signature = getSigConstantOperand();
                        if ("()V".equals(signature)) {
                            sawAlloc = true;
                        }
                    }
                }
            break;

            case INVOKEINTERFACE:
                String methodName = getNameConstantOperand();
                if ("add".equals(methodName) || "addAll".equals(methodName)) {
                    String signature = getSigConstantOperand();
                    Type[] argTypes = Type.getArgumentTypes(signature);
                    if ((argTypes.length == 1) && (stack.getStackDepth() > 1)) {
                        OpcodeStack.Item item = stack.getStackItem(1);
                        Integer allocNum = (Integer) item.getUserValue();
                        if (allocNum != null) {
                            List<Integer> lines = allocToAddPCs.get(allocNum);
                            if (lines == null) {
                                lines = new ArrayList<Integer>();
                                allocToAddPCs.put(allocNum, lines);
                            }
                            lines.add(getPC());
                        }
                    }
                }
                break;

            case GOTO:
            case GOTO_W:
                if (getBranchOffset() < 0) {
                    int target = getBranchTarget();
                    Iterator<List<Integer>> it = allocToAddPCs.values().iterator();
                    while (it.hasNext()) {
                        List<Integer> pcs = it.next();
                        for (Integer pc : pcs) {
                            if (pc > target) {
                                bugReporter.reportBug(new BugInstance(this, "PSC_PRESIZE_COLLECTIONS", NORMAL_PRIORITY)
                                            .addClass(this)
                                            .addMethod(this)
                                            .addSourceLine(this, pc));
                                it.remove();
                                break;
                            }
                        }
                    }
                }
            }
        } finally {
            stack.sawOpcode(this, seen);
            if (sawAlloc) {
                if (stack.getStackDepth() > 0) {
                    OpcodeStack.Item item = stack.getStackItem(0);
                    item.setUserValue(Integer.valueOf(++allocNumber));
                }
            }
        }
    }
}
