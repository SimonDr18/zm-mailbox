package com.zimbra.qa.unittest;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import junit.framework.TestCase;
import junit.framework.TestSuite;

import com.zimbra.common.service.ServiceException;
import com.zimbra.common.util.CliUtil;

import com.zimbra.cs.account.AccessManager;
import com.zimbra.cs.account.Account;
import com.zimbra.cs.account.AuthToken;
import com.zimbra.cs.account.DistributionList;
import com.zimbra.cs.account.Domain;
import com.zimbra.cs.account.Provisioning;
import com.zimbra.cs.account.accesscontrol.PermUtil;
import com.zimbra.cs.account.accesscontrol.Right;
import com.zimbra.cs.account.accesscontrol.ZimbraACE;
import com.zimbra.cs.mailbox.ACL;
import com.zimbra.cs.service.AuthProvider;

public class TestACL extends TestCase {
    // private static final AclAccessManager mAM = new AclAccessManager();
    private static final AccessManager mAM = AccessManager.getInstance();
    private static final String TEST_ID = TestProvisioningUtil.genTestId();
    private static final String DOMAIN_NAME = TestProvisioningUtil.baseDomainName("test-ACL", TEST_ID);
    
    static {
        Provisioning prov = Provisioning.getInstance();
        try {
            // create a domain
            Domain domain = prov.createDomain(DOMAIN_NAME, new HashMap<String, Object>());
        } catch (ServiceException e) {
            e.printStackTrace();
            fail();
        }
    }
    
    private static String getEmailAddr(String localPart) {
        return localPart + "@" + DOMAIN_NAME;
    }
    
    private Account guestAccount(String email, String password) {
        return new ACL.GuestAccount(email, password);
    }
    
    private Account anonAccount() {
        return ACL.ANONYMOUS_ACCT;
    }
    
    /*
     * verify we always get the expected result, regardless what the default value is
     * This test does NOT use the admin privileges 
     * 
     * This is for testing target entry with some ACL.
     */
    private void verify(Account grantee, Account target, Right right, boolean expected) throws Exception {
        verify(grantee, target, right, false, true, expected);
    }
    
    /*
     * verify we always get the expected result, regardless what the default value is
     * 
     * This is for testing target entry with some ACL.
     */
    private void verify(Account grantee, Account target, Right right, boolean asAdmin, boolean expected) throws Exception {
        // 1. pass true as the default value, result should not be affected by the default value
        verify(grantee, target, right, asAdmin, true, expected);
        
        // 2. pass false as the default value, result should not be affected by the default value
        verify(grantee, target, right, asAdmin, false, expected);
    }
    
    /*
     * verify that the result IS the default value
     * 
     * This is for testing target entry without any ACL.
     */
    private void verifyDefault(Account grantee, Account target, Right right) throws Exception {
        boolean asAdmin = false; // TODO: test admin case
        
        // 1. pass true as the default value, result should be true
        verify(grantee, target, right, asAdmin, true, true);
            
        // 2. pass false as the default value, result should be false
        verify(grantee, target, right, asAdmin, false, false);
    }
    
    /*
     * verify expected result
     */
    private void verify(Account grantee, Account target, Right right, boolean asAdmin, boolean defaultValue, boolean expected) throws Exception {
        boolean result;
        
        // Account interface
        result = mAM.canPerform(grantee==null?null:grantee, target, right, asAdmin, defaultValue);
        assertEquals(expected, result);
        
        // AuthToken interface
        result = mAM.canPerform(grantee==null?null:AuthProvider.getAuthToken(grantee), target, right, asAdmin, defaultValue);
        assertEquals(expected, result);
        
        // String interface
        result = mAM.canPerform(grantee==null?null:grantee.getName(), target, right, asAdmin, defaultValue);
        assertEquals(expected, result);
    }
    
    /*
     * test Zimbra user grantee
     */
    public void testZimbra() throws Exception {
        Provisioning prov = Provisioning.getInstance();
        
        /*
         * setup grantees
         */
        Account goodguy = prov.createAccount(getEmailAddr("goodguy"), "test123", null);
        Account badguy = prov.createAccount(getEmailAddr("badguy"), "test123", null);
        Account wrongguy = prov.createAccount(getEmailAddr("wrongguy"), "test123", null);
        Account nobody = prov.createAccount(getEmailAddr("nobody"), "test123", null);
        
        Map<String, Object> attrs = new HashMap<String, Object>();
        attrs.put(Provisioning.A_zimbraIsDomainAdminAccount, Provisioning.TRUE);
        Account admin = prov.createAccount(getEmailAddr("admin"), "test123", attrs);
        
        /*
         * setup targets
         */
        Account target = prov.createAccount(getEmailAddr("target-test-zimbra"), "test123", null);
        Set<ZimbraACE> aces = new HashSet<ZimbraACE>();
        aces.add(new ZimbraACE(goodguy, Right.RT_viewFreeBusy, false));
        aces.add(new ZimbraACE(goodguy, Right.RT_invite, false));
        aces.add(new ZimbraACE(badguy, Right.RT_viewFreeBusy, true));
        aces.add(new ZimbraACE(wrongguy, Right.RT_viewFreeBusy, true));
        aces.add(new ZimbraACE(wrongguy, Right.RT_viewFreeBusy, false));
        aces.add(new ZimbraACE(ACL.ANONYMOUS_ACCT, Right.RT_viewFreeBusy, false));
        PermUtil.modifyACEs(target, aces);
        
        // self should always be allowed
        verify(target, target, Right.RT_invite, true);
        
        // admin access using admin privileges
        verify(admin, target, Right.RT_invite, true, true);
        
        // admin access NOT using admin privileges
        verify(admin, target, Right.RT_invite, false, false);
        
        // specifically allowed
        verify(goodguy, target, Right.RT_viewFreeBusy, true);
        verify(goodguy, target, Right.RT_invite, true);
        
        // specifically denied
        verify(badguy, target, Right.RT_viewFreeBusy, false);
        
        // specifically allowed and denied
        verify(wrongguy, target, Right.RT_viewFreeBusy, false);
        
        // not specifically allowed or denied, but PUB is allowed
        verify(nobody, target, Right.RT_viewFreeBusy, true);
        
        // not specifically allowed or denied
        verify(nobody, target, Right.RT_invite, false);
    }
    
    /*
     * test guest(with a non-Zimbra email address, and a password) grantee
     * Note: GST grantee is not yet implemented for now, the result will be the same as PUB grantee, which is supported)
     */
    public void testGuest() throws Exception {
        Provisioning prov = Provisioning.getInstance();
        
        /*
         * setup grantees
         */
        Account guest = guestAccount("guest@external.com", "whocares");
        
        /*
         * setup targets
         */
        Account target = prov.createAccount(getEmailAddr("target-test-guest"), "test123", null);
        Set<ZimbraACE> aces = new HashSet<ZimbraACE>();
        aces.add(new ZimbraACE(ACL.ANONYMOUS_ACCT, Right.RT_viewFreeBusy, false));
        PermUtil.modifyACEs(target, aces);
        
        // right allowed for PUB
        verify(guest, target, Right.RT_viewFreeBusy, true);
        
        // right not in ACL
        verify(guest, target, Right.RT_invite, false);
    }
    
    /*
     * test anonymous(without any identity) grantee
     */
    public void testAnon() throws Exception {
        Provisioning prov = Provisioning.getInstance();
        
        /*
         * setup grantees
         */
        Account anon = anonAccount();
        
        /*
         * setup targets
         */
        Account target = prov.createAccount(getEmailAddr("target-test-anon"), "test123", null);
        Set<ZimbraACE> aces = new HashSet<ZimbraACE>();
        aces.add(new ZimbraACE(ACL.ANONYMOUS_ACCT, Right.RT_viewFreeBusy, false));
        PermUtil.modifyACEs(target, aces);
        
        // anon grantee
        verify(anon, target, Right.RT_viewFreeBusy, true);
        verify(anon, target, Right.RT_invite, false);
        
        // null grantee
        verify(null, target, Right.RT_viewFreeBusy, true);
        verify(null, target, Right.RT_invite, false);
    }
    
    public void testGroup() throws Exception {
        Provisioning prov = Provisioning.getInstance();
        
        /*
         * setup grantees
         */
        Account user1 = prov.createAccount(getEmailAddr("user1"), "test123", null);
        Account user2 = prov.createAccount(getEmailAddr("user2"), "test123", null);
        Account user3 = prov.createAccount(getEmailAddr("user3"), "test123", null);
        
        /*
         * setup groups
         */
        DistributionList groupA = prov.createDistributionList(getEmailAddr("group-a"), new HashMap<String, Object>());
        prov.addMembers(groupA, new String[] {user1.getName(), user2.getName()});
        
        /*
         * setup targets
         */
        Account target = prov.createAccount(getEmailAddr("target-test-group"), "test123", null);
        Set<ZimbraACE> aces = new HashSet<ZimbraACE>();
        aces.add(new ZimbraACE(user1, Right.RT_viewFreeBusy, true));
        aces.add(new ZimbraACE(groupA, Right.RT_viewFreeBusy, false));
        PermUtil.modifyACEs(target, aces);
        
        // group member, but account is specifically denied
        verify(user1, target, Right.RT_viewFreeBusy, false);
        
        // group member
        verify(user2, target, Right.RT_viewFreeBusy, true);
        
        // not group member
        verify(user3, target, Right.RT_viewFreeBusy, false);
    }
    
    /*
     * test target with no ACL, should return caller default regardless who is the grantee and which right to ask for
     */
    public void testNoACL() throws Exception {
        Provisioning prov = Provisioning.getInstance();
        
        /*
         * setup grantees
         */
        Account zimbraUser = prov.createAccount(getEmailAddr("zimbra-user"), "test123", null);
        Account guest = guestAccount("guest@external.com", "whocares");
        Account anon = anonAccount();
        
        /*
         * setup targets
         */
        Account target = prov.createAccount(getEmailAddr("target-no-acl"), "test123", null);
        
        for (Right right : Right.values()) {
            verifyDefault(zimbraUser, target, right);
            verifyDefault(guest, target, right);
            verifyDefault(anon, target, right);
        }
    }

    public static void main(String[] args) throws Exception {
        CliUtil.toolSetup("INFO");
        TestUtil.runTest(new TestSuite(TestACL.class));
    }
}
