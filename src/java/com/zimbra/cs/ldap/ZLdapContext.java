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
package com.zimbra.cs.ldap;

import com.zimbra.common.service.ServiceException;
import com.zimbra.cs.account.Account;
import com.zimbra.cs.account.AccountServiceException;
import com.zimbra.cs.ldap.LdapException.LdapEntryAlreadyExistException;
import com.zimbra.cs.ldap.LdapTODO.TODOEXCEPTIONMAPPING;

public abstract class ZLdapContext extends ZLdapElement implements ILdapContext {

    public abstract void closeContext();
    
    public abstract void createEntry(ZMutableEntry entry) throws 
    LdapEntryAlreadyExistException, ServiceException;
    
    public abstract void createEntry(String dn, String objectClass, String[] attrs) 
    throws ServiceException;
    
    public abstract void createEntry(String dn, String[] objectClasses, String[] attrs) 
    throws ServiceException;
    
    public abstract ZModificationList createModiftcationList();
    
    public abstract void deleteChildren(String dn) throws ServiceException;
    
    public abstract ZAttributes getAttributes(String dn) throws LdapException;
    
    public abstract ZLdapSchema getSchema() throws LdapException;
    
    public abstract void modifyAttributes(String dn, ZModificationList modList) 
    throws LdapException;
    
    public abstract void moveChildren(String oldDn, String newDn) throws 
    ServiceException;
    
    public abstract void renameEntry(String oldDn, String newDn) throws 
    LdapException;
    
    public abstract void replaceAttributes(String dn, ZAttributes attrs) 
    throws LdapException;
    
    /**
     * Important Note: caller is responsible to close the ZimbraLdapContext
     * 
     * This API does paged search, results are returned via the search 
     * visitor interface.
     */
    public abstract void searchPaged(SearchLdapOptions searchOptions)
    throws ServiceException;
    
    public abstract ZSearchResultEnumeration searchDir(
            String baseDN, ZLdapFilter filter, ZSearchControls searchControls) 
    throws LdapException;
   
    public abstract void unbindEntry(String dn) throws LdapException;

}
