/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.oak.jcr.security.authorization;

import java.security.Principal;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import javax.jcr.Node;
import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.security.AccessControlEntry;
import javax.jcr.security.AccessControlList;
import javax.jcr.security.Privilege;
import javax.jcr.util.TraversingItemVisitor;

import com.google.common.collect.Sets;
import org.apache.jackrabbit.api.security.JackrabbitAccessControlManager;
import org.apache.jackrabbit.api.security.user.Group;
import org.apache.jackrabbit.commons.jackrabbit.authorization.AccessControlUtils;
import org.apache.jackrabbit.oak.spi.security.principal.EveryonePrincipal;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;

/**
 * Permission evaluation tests related to {@link javax.jcr.security.Privilege#JCR_READ} privilege.
 */
public class ReadTest extends AbstractEvaluationTest {

    @Test
    public void testChildNodes() throws Exception {
        /* create some new nodes below 'path' */
        Node n = superuser.getNode(path);
        for (int i = 0; i < 5; i++) {
            n = n.addNode(nodeName4, testNodeType);
        }
        superuser.save();

        /* make sure the same privileges/permissions are granted as at path. */
        testSession.refresh(false);
        String childPath = n.getPath();
        assertArrayEquals(readPrivileges, testAcMgr.getPrivileges(childPath));
        testSession.checkPermission(childPath, Session.ACTION_READ);
    }

    @Test
    public void testNonExistingItem() throws Exception {
        /*
          precondition:
          testuser must have READ-only permission on the root node and below
        */
        String rootPath = testSession.getRootNode().getPath();
        assertReadOnly(rootPath);
        testSession.checkPermission(rootPath + "nonExistingItem", Session.ACTION_READ);
    }

    @Test
    public void testGetItem() throws Exception {
        // withdraw READ privilege to 'testUser' at 'path'
        deny(path, readPrivileges);
        allow(childNPath, readPrivileges);
        testSession.getItem(childNPath);
    }

    @Test
    public void testItemExists() throws Exception {
        // withdraw READ privilege to 'testUser' at 'path'
        deny(path, readPrivileges);
        allow(childNPath, readPrivileges);

        assertFalse(testSession.itemExists(path));
        assertTrue(testSession.itemExists(childNPath));
    }

    @Test
    public void testDeniedReadOnSubTree() throws Exception, InterruptedException {
        // withdraw READ privilege to 'testUser' at 'path'
        deny(childNPath, readPrivileges);
        /*
         testuser must now have
         - READ-only permission at path
         - READ-only permission for the child-props of path

         testuser must not have
         - any permission on child-node and all its subtree
        */

        // must still have read-access to path, ...
        assertTrue(testSession.hasPermission(path, Session.ACTION_READ));
        Node n = testSession.getNode(path);
        // ... siblings of childN
        testSession.getNode(childNPath2);
        // ... and props of path
        assertTrue(n.getProperties().hasNext());

        //testSession must not have access to 'childNPath'
        assertFalse(testSession.itemExists(childNPath));
        try {
            testSession.getNode(childNPath);
            fail("Read access has been denied -> cannot retrieve child node.");
        } catch (PathNotFoundException e) {
            // ok.
        }
        /*
        -> must not have access to subtree below 'childNPath'
        */
        assertFalse(testSession.itemExists(childchildPPath));
        try {
            testSession.getItem(childchildPPath);
            fail("Read access has been denied -> cannot retrieve prop below child node.");
        } catch (PathNotFoundException e) {
            // ok.
        }
    }

    @Test
    public void testAllowWriteDenyRead() throws Exception {
        // allow 'testUser' to write at 'path'
        allow(path, repWritePrivileges);
        // deny read access
        deny(path, readPrivileges);

        // testuser must not be able to access that node
        assertFalse(testSession.nodeExists(path));
    }

    @Test
    public void testDenyRoot() throws Exception {
        Set<AccessControlEntry> acesBefore = getACEs("/");
        try {
            deny("/", readPrivileges);
            testSession.getRootNode();
            fail("root should not be accessible");
        } catch (Exception e) {
            // expected exception
        } finally {
            restoreAces("/", acesBefore);
        }
    }

    private Set<AccessControlEntry> getACEs(String path) throws RepositoryException {
        AccessControlList acl = AccessControlUtils.getAccessControlList(superuser, path);
        Set<AccessControlEntry> acesBefore = Sets.newHashSet();
        if (acl != null) {
            Collections.addAll(acesBefore, acl.getAccessControlEntries());
        }
        return acesBefore;
    }

    private void restoreAces(String path, Set<AccessControlEntry> acesToKeep) throws RepositoryException {
        AccessControlList acl = AccessControlUtils.getAccessControlList(superuser, path);
        if (acl != null) {
            for (AccessControlEntry ace : acl.getAccessControlEntries()) {
                if (!acesToKeep.contains(ace)) {
                    acl.removeAccessControlEntry(ace);
                }
            }
            acMgr.setPolicy("/", acl);
            superuser.save();
        }
    }

    @Test
    public void testDenyPath() throws Exception {
        try {
            deny(path, readPrivileges);
            testSession.getNode(path);
            fail("nodet should not be accessible");
        } catch (Exception e) {
            // expected exception
        }
    }

    @Test
    public void testReadDenied() throws Exception {
        /* deny READ privilege for testUser at 'path' */
        deny(path, readPrivileges);
        /*
         allow READ privilege for testUser at 'childNPath'
         */
        allow(childNPath, readPrivileges);

        assertFalse(testSession.nodeExists(path));
        assertTrue(testSession.nodeExists(childNPath));
        Node n = testSession.getNode(childNPath);
        n.getDefinition();
    }

    @Test
    public void testDenyUserAllowGroup() throws Exception {
        /*
         deny READ privilege for testUser at 'path'
         */
        deny(path, testUser.getPrincipal(), readPrivileges);
        /*
         allow READ privilege for group at 'path'
         */
        allow(path, getTestGroup().getPrincipal(), readPrivileges);

        assertFalse(testSession.nodeExists(path));
    }

    @Test
    public void testAllowGroupDenyUser() throws Exception {
        /*
        allow READ privilege for group at 'path'
        */
        allow(path, getTestGroup().getPrincipal(), readPrivileges);
        /*
        deny READ privilege for testUser at 'path'
        */
        deny(path, testUser.getPrincipal(), readPrivileges);

        assertFalse(testSession.nodeExists(path));
    }

    @Test
    public void testAllowUserDenyGroup() throws Exception {
        /*
         allow READ privilege for testUser at 'path'
         */
        allow(path, testUser.getPrincipal(), readPrivileges);
        /*
         deny READ privilege for group at 'path'
         */
        deny(path, getTestGroup().getPrincipal(), readPrivileges);

        assertTrue(testSession.nodeExists(path));
    }

    @Test
    public void testDenyGroupAllowUser() throws Exception {
        /*
         deny READ privilege for group at 'path'
         */
        deny(path, getTestGroup().getPrincipal(), readPrivileges);

        /*
         allow READ privilege for testUser at 'path'
         */
        allow(path, testUser.getPrincipal(), readPrivileges);

        assertTrue(testSession.nodeExists(path));
    }

    @Test
    public void testDenyGroupAllowEveryone() throws Exception {
        /*
         deny READ privilege for group at 'path'
         */
        deny(path, getTestGroup().getPrincipal(), readPrivileges);

        /*
         allow READ privilege for everyone at 'path'
         */
        allow(path, EveryonePrincipal.getInstance(), readPrivileges);

        assertTrue(testSession.nodeExists(path));
    }

    @Test
    public void testAllowEveryoneDenyGroup() throws Exception {
        /*
         allow READ privilege for everyone at 'path'
         */
        allow(path, EveryonePrincipal.getInstance(), readPrivileges);

        /*
         deny READ privilege for group at 'path'
         */
        deny(path, getTestGroup().getPrincipal(), readPrivileges);

        assertFalse(testSession.nodeExists(path));
    }

    @Test
    public void testDenyGroupPathAllowEveryoneChildPath() throws Exception {
        /*
         deny READ privilege for group at 'path'
         */
        deny(path, getTestGroup().getPrincipal(), readPrivileges);

        /*
         allow READ privilege for everyone at 'childNPath'
         */
        allow(path, EveryonePrincipal.getInstance(), readPrivileges);

        assertTrue(testSession.nodeExists(childNPath));
    }

    @Test
    public void testAllowEveryonePathDenyGroupChildPath() throws Exception {
        /*
         allow READ privilege for everyone at 'path'
         */
        allow(path, EveryonePrincipal.getInstance(), readPrivileges);

        /*
         deny READ privilege for group at 'childNPath'
         */
        deny(childNPath, getTestGroup().getPrincipal(), readPrivileges);

        assertFalse(testSession.nodeExists(childNPath));
    }

    @Test
    public void testAllowUserPathDenyGroupChildPath() throws Exception {
        /*
         allow READ privilege for testUser at 'path'
         */
        allow(path, testUser.getPrincipal(), readPrivileges);
        /*
         deny READ privilege for group at 'childPath'
         */
        deny(path, getTestGroup().getPrincipal(), readPrivileges);

        assertTrue(testSession.nodeExists(childNPath));
    }

    @Test
    public void testDenyGroupPathAllowUserChildPath() throws Exception {
        /*
         deny READ privilege for group at 'path'
         */
        deny(path, getTestGroup().getPrincipal(), readPrivileges);

        /*
         allow READ privilege for testUser at 'childNPath'
         */
        allow(path, testUser.getPrincipal(), readPrivileges);

        assertTrue(testSession.nodeExists(childNPath));
    }

    @Test
    public void testDenyUserPathAllowGroupChildPath() throws Exception {
        /*
         deny READ privilege for testUser at 'path'
         */
        deny(path, testUser.getPrincipal(), readPrivileges);
        /*
         allow READ privilege for group at 'childNPath'
         */
        allow(path, getTestGroup().getPrincipal(), readPrivileges);

        assertFalse(testSession.nodeExists(childNPath));
    }

    @Test
    public void testAllowGroupPathDenyUserChildPath() throws Exception {
        /*
        allow READ privilege for the group at 'path'
        */
        allow(path, getTestGroup().getPrincipal(), readPrivileges);
        /*
        deny READ privilege for testUser at 'childNPath'
        */
        deny(path, testUser.getPrincipal(), readPrivileges);

        assertFalse(testSession.nodeExists(childNPath));
    }

    @Test
    public void testGlobRestriction() throws Exception {
        deny(path, readPrivileges, createGlobRestriction("*/" + jcrPrimaryType));

        assertTrue(testAcMgr.hasPrivileges(path, readPrivileges));
        assertTrue(testSession.hasPermission(path, javax.jcr.Session.ACTION_READ));
        testSession.getNode(path);

        assertTrue(testAcMgr.hasPrivileges(childNPath, readPrivileges));
        assertTrue(testSession.hasPermission(childNPath, javax.jcr.Session.ACTION_READ));
        testSession.getNode(childNPath);

        String propPath = path + '/' + jcrPrimaryType;
        assertFalse(testSession.hasPermission(propPath, javax.jcr.Session.ACTION_READ));
        assertFalse(testSession.propertyExists(propPath));

        propPath = childNPath + '/' + jcrPrimaryType;
        assertFalse(testSession.hasPermission(propPath, javax.jcr.Session.ACTION_READ));
        assertFalse(testSession.propertyExists(propPath));
    }

    @Test
    public void testGlobRestriction2() throws Exception {
        Group group2 = getUserManager(superuser).createGroup("group2");
        Group group3 = getUserManager(superuser).createGroup("group3");
        superuser.save();

        try {
            Privilege[] readPrivs = privilegesFromName(Privilege.JCR_READ);

            modify(path, getTestGroup().getPrincipal(), readPrivs, true, createGlobRestriction("/*"));
            allow(path, group2.getPrincipal(), readPrivs);
            deny(path, group3.getPrincipal(), readPrivs);

            Set<Principal> principals = new HashSet();
            principals.add(getTestGroup().getPrincipal());
            principals.add(group2.getPrincipal());
            principals.add(group3.getPrincipal());

            assertFalse(((JackrabbitAccessControlManager) acMgr).hasPrivileges(path, principals, readPrivs));
            assertFalse(((JackrabbitAccessControlManager) acMgr).hasPrivileges(childNPath, principals, readPrivs));
        } finally {
            group2.remove();
            group3.remove();
            superuser.save();
        }
    }

    @Test
    public void testGlobRestriction3() throws Exception {
        Group group2 = getUserManager(superuser).createGroup("group2");
        Group group3 = getUserManager(superuser).createGroup("group3");
        superuser.save();

        try {
            Privilege[] readPrivs = privilegesFromName(Privilege.JCR_READ);

            allow(path, group2.getPrincipal(), readPrivs);
            deny(path, group3.getPrincipal(), readPrivs);
            modify(path, getTestGroup().getPrincipal(), readPrivs, true, createGlobRestriction("/*"));

            Set<Principal> principals = new HashSet();
            principals.add(getTestGroup().getPrincipal());
            principals.add(group2.getPrincipal());
            principals.add(group3.getPrincipal());

            assertFalse(((JackrabbitAccessControlManager) acMgr).hasPrivileges(path, principals, readPrivs));
            assertTrue(((JackrabbitAccessControlManager) acMgr).hasPrivileges(childNPath, principals, readPrivs));
        } finally {
            group2.remove();
            group3.remove();
            superuser.save();
        }
    }

    @Test
    public void testGlobRestriction4()throws Exception{
        Node a = superuser.getNode(path).addNode("a");
        allow(path, readPrivileges);
        deny(path, readPrivileges, createGlobRestriction("*/anotherpath"));

        String aPath = a.getPath();
        assertTrue(testSession.nodeExists(aPath));
        Node n = testSession.getNode(aPath);

        Node test = testSession.getNode(path);
        assertTrue(test.hasNode("a"));
        Node n2 = test.getNode("a");
        assertTrue(n.isSame(n2));
    }

    @Test
    public void testGlobRestriction5()throws Exception{
        Node a = superuser.getNode(path).addNode("a");
        allow(path, readPrivileges);
        deny(path, readPrivileges, createGlobRestriction("*/anotherpath"));
        allow(a.getPath(), repWritePrivileges);

        String aPath = a.getPath();
        assertTrue(testSession.nodeExists(aPath));
        Node n = testSession.getNode(aPath);

        Node test = testSession.getNode(path);
        assertTrue(test.hasNode("a"));
        Node n2 = test.getNode("a");
        assertTrue(n.isSame(n2));
    }

    /**
     * @see <a href="https://issues.apache.org/jira/browse/OAK-878">OAK-878 :
     * IllegalArgumentException while adding/removing permission to user/group</a>
     */
    @Test
    public void testImplicitReorder() throws Exception{
        allow(path, testUser.getPrincipal(), readPrivileges);
        assertEntry(0, true);

        allow(path, getTestGroup().getPrincipal(), readPrivileges);
        assertEntry(0, true);

        deny(path, testUser.getPrincipal(), readPrivileges);
        assertEntry(1, false);

        deny(path, getTestGroup().getPrincipal(), readPrivileges);
        assertEntry(0, false);

        allow(path, testUser.getPrincipal(), readPrivileges);
    }

    private void assertEntry(final int index, final boolean isAllow) throws RepositoryException {
        AccessControlEntry first = AccessControlUtils.getAccessControlList(superuser, path).getAccessControlEntries()[index];

        assertEquals(testUser.getPrincipal(), first.getPrincipal());

        Node n = superuser.getNode("/jcr:system/rep:permissionStore/default/" + testUser.getPrincipal().getName());
        TraversingItemVisitor v = new TraversingItemVisitor.Default(true, -1) {
            @Override
            protected void entering(Node node, int level) throws RepositoryException {
                if (node.isNodeType("rep:Permissions")
                        && node.hasProperty("rep:accessControlledPath")
                        && path.equals(node.getProperty("rep:accessControlledPath").getString())) {
                    assertEquals(index, node.getProperty("rep:index").getLong());
                    assertEquals(isAllow, node.getProperty("rep:isAllow").getBoolean());
                }
            }
        };
        v.visit(n);
    }
}
