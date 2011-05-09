/*
 * ***** BEGIN LICENSE BLOCK *****
 * Zimbra Collaboration Suite Server
 * Copyright (C) 2011 Zimbra, Inc.
 * 
 * The contents of this file are subject to the Zimbra Public License
 * Version 1.3 ("License"); you may not use this file except in
 * compliance with the License.  You may obtain a copy of the License at
 * http://www.zimbra.com/license.
 * 
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied.
 * ***** END LICENSE BLOCK *****
 */
package com.zimbra.qa.unittest;

import java.util.Map;

import org.junit.*;
import static org.junit.Assert.*;

import com.zimbra.cs.account.Account;
import com.zimbra.cs.account.DistributionList;
import com.zimbra.cs.account.Domain;
import com.zimbra.cs.account.Provisioning;
import com.zimbra.cs.account.Provisioning.AccountBy;
import com.zimbra.cs.account.Provisioning.CacheEntryType;
import com.zimbra.cs.account.Provisioning.DistributionListBy;


public class TestLdapProvAlias {
    private static Provisioning prov;
    private static Domain domain;
    
    @BeforeClass
    public static void init() throws Exception {
        TestLdap.manualInit();
        
        prov = Provisioning.getInstance();
        domain = TestLdapProvDomain.createDomain(prov, baseDomainName(), null);
    }
    
    @AfterClass
    public static void cleanup() throws Exception {
        String baseDomainName = baseDomainName();
        TestLdap.deleteEntireBranch(baseDomainName);
    }
    
    private static String baseDomainName() {
        return TestLdapProvAlias.class.getName().toLowerCase();
    }
    
    private Account createAccount(String localPart) throws Exception {
        return createAccount(localPart, null);
    }
    
    private DistributionList createDistributionList(String localpart) throws Exception {
        return createDistributionList(localpart, null);
    }
    
    private DistributionList createDistributionList(String localPart, Map<String, Object> attrs) 
    throws Exception {
        return TestLdapProvDistributionList.createDistributionList(prov, localPart, domain, attrs);
    }
    
    private void deleteDistributionList(DistributionList dl) throws Exception {
        TestLdapProvDistributionList.deleteDistributionList(prov, dl);
    }
    
    private Account createAccount(String localPart, Map<String, Object> attrs) throws Exception {
        return TestLdapProvAccount.createAccount(prov, localPart, domain, attrs);
    }
    
    private void deleteAccount(Account acct) throws Exception {
        TestLdapProvAccount.deleteAccount(prov, acct);
    }
    
    @Test
    public void addAccountAlias() throws Exception {
        String ACCT_NAME_LOCALPART = TestLdap.makeAliasNameLocalPart("addAccountAlias-acct");
        Account acct = createAccount(ACCT_NAME_LOCALPART);
        String ACCT_ID = acct.getId();
        
        String ALIAS_LOCALPART = TestLdap.makeAliasNameLocalPart("addAccountAlias-alias");
        String ALIAS_NAME = TestUtil.getAddress(ALIAS_LOCALPART, domain.getName());
        
        prov.addAlias(acct, ALIAS_NAME);
        
        prov.flushCache(CacheEntryType.account, null);
        Account acctByAlias = prov.get(AccountBy.name, ALIAS_NAME);
        
        assertEquals(ACCT_ID, acctByAlias.getId());
        
        deleteAccount(acctByAlias);
        
        // get account by alias again
        prov.flushCache(CacheEntryType.account, null);
        acctByAlias = prov.get(AccountBy.name, ALIAS_NAME);
        assertNull(acctByAlias);
    }
    
    @Test
    public void removeAccountAlias() throws Exception {
        String ACCT_NAME_LOCALPART = TestLdap.makeAliasNameLocalPart("removeAccountAlias-acct");
        Account acct = createAccount(ACCT_NAME_LOCALPART);
        String ACCT_ID = acct.getId();
        
        String ALIAS_LOCALPART = TestLdap.makeAliasNameLocalPart("removeAccountAlias-alias");
        String ALIAS_NAME = TestUtil.getAddress(ALIAS_LOCALPART, domain.getName());
        
        prov.addAlias(acct, ALIAS_NAME);
        
        prov.flushCache(CacheEntryType.account, null);
        Account acctByAlias = prov.get(AccountBy.name, ALIAS_NAME);
        
        assertEquals(ACCT_ID, acctByAlias.getId());
        
        prov.removeAlias(acct, ALIAS_NAME);
        
        prov.flushCache(CacheEntryType.account, null);
        acctByAlias = prov.get(AccountBy.name, ALIAS_NAME);
        
        assertNull(acctByAlias);
        
        deleteAccount(acct);
    }
    
    @Test
    public void addDistributionListAlias() throws Exception {
        String DL_NAME_LOCALPART = TestLdap.makeAliasNameLocalPart("addDistributionListAlias-dl");
        DistributionList dl = createDistributionList(DL_NAME_LOCALPART);
        String DL_ID = dl.getId();
        
        String ALIAS_LOCALPART = TestLdap.makeAliasNameLocalPart("addDistributionListAlias-alias");
        String ALIAS_NAME = TestUtil.getAddress(ALIAS_LOCALPART, domain.getName());
        
        prov.addAlias(dl, ALIAS_NAME);
        
        prov.flushCache(CacheEntryType.account, null);
        DistributionList dlByAlias = prov.get(DistributionListBy.name, ALIAS_NAME);
        
        assertEquals(DL_ID, dlByAlias.getId());
        
        deleteDistributionList(dlByAlias);
        
        // get dl by alias again
        prov.flushCache(CacheEntryType.group, null);
        dlByAlias = prov.get(DistributionListBy.name, ALIAS_NAME);
        assertNull(dlByAlias);
    }
    
    @Test
    public void removeDistributionListAlias() throws Exception {
        String DL_NAME_LOCALPART = TestLdap.makeAliasNameLocalPart("removeDistributionListAlias-dl");
        DistributionList dl = createDistributionList(DL_NAME_LOCALPART);
        String DL_ID = dl.getId();
        
        String ALIAS_LOCALPART = TestLdap.makeAliasNameLocalPart("removeDistributionListAlias-alias");
        String ALIAS_NAME = TestUtil.getAddress(ALIAS_LOCALPART, domain.getName());
        
        prov.addAlias(dl, ALIAS_NAME);
        
        prov.flushCache(CacheEntryType.group, null);
        DistributionList dlByAlias = prov.get(DistributionListBy.name, ALIAS_NAME);
        
        assertEquals(DL_ID, dlByAlias.getId());
        
        prov.removeAlias(dl, ALIAS_NAME);
        
        prov.flushCache(CacheEntryType.group, null);
        dlByAlias = prov.get(DistributionListBy.name, ALIAS_NAME);
        
        assertNull(dlByAlias);
        
        deleteDistributionList(dl);
    }
}
