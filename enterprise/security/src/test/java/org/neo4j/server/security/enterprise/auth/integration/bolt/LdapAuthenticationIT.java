/*
 * Copyright (c) 2002-2016 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.server.security.enterprise.auth.integration.bolt;

import org.apache.directory.server.annotations.CreateLdapServer;
import org.apache.directory.server.annotations.CreateTransport;
import org.apache.directory.server.core.annotations.ApplyLdifFiles;
import org.apache.directory.server.core.annotations.ContextEntry;
import org.apache.directory.server.core.annotations.CreateDS;
import org.apache.directory.server.core.annotations.CreatePartition;
import org.apache.directory.server.core.annotations.LoadSchema;
import org.apache.directory.server.core.integ.AbstractLdapTestUnit;
import org.apache.directory.server.core.integ.FrameworkRunner;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.Map;
import java.util.function.Consumer;

import org.neo4j.bolt.v1.transport.integration.Neo4jWithSocket;
import org.neo4j.bolt.v1.transport.integration.TransportTestUtil;
import org.neo4j.bolt.v1.transport.socket.client.Connection;
import org.neo4j.bolt.v1.transport.socket.client.SecureSocketConnection;
import org.neo4j.function.Factory;
import org.neo4j.graphdb.config.Setting;
import org.neo4j.graphdb.factory.GraphDatabaseSettings;
import org.neo4j.helpers.HostnamePort;
import org.neo4j.kernel.api.exceptions.Status;
import org.neo4j.server.security.enterprise.auth.SecuritySettings;
import org.neo4j.test.TestEnterpriseGraphDatabaseFactory;
import org.neo4j.test.TestGraphDatabaseFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.neo4j.bolt.v1.messaging.message.Messages.init;
import static org.neo4j.bolt.v1.messaging.message.Messages.pullAll;
import static org.neo4j.bolt.v1.messaging.message.Messages.run;
import static org.neo4j.bolt.v1.messaging.util.MessageMatchers.msgFailure;
import static org.neo4j.bolt.v1.messaging.util.MessageMatchers.msgSuccess;
import static org.neo4j.bolt.v1.transport.integration.TransportTestUtil.eventuallyRecieves;
import static org.neo4j.helpers.collection.MapUtil.map;

@RunWith( FrameworkRunner.class )
@CreateDS(
        name = "Test",
        partitions = {@CreatePartition(
                name = "example",
                suffix = "dc=example,dc=com", contextEntry = @ContextEntry(
                entryLdif = "dn: dc=example,dc=com\n" +
                        "dc: example\n" +
                        "o: example\n" +
                        "objectClass: top\n" +
                        "objectClass: dcObject\n" +
                        "objectClass: organization\n\n" ) )}, loadedSchemas = {
        @LoadSchema( name = "nis", enabled = true ),
        @LoadSchema( name = "posix", enabled = false )} )
@CreateLdapServer( transports = {@CreateTransport( protocol = "LDAP", port = 10389, address = "0.0.0.0" )} )
public class LdapAuthenticationIT extends AbstractLdapTestUnit
{
    @Rule
    public Neo4jWithSocket server = new Neo4jWithSocket( getTestGraphDatabaseFactory(), getSettingsFunction() );

    private void restartNeo4jServerWithOverriddenSettings( Consumer<Map<Setting<?>, String>> overrideSettingsFunction )
            throws IOException
    {
        server.restartDatabase( overrideSettingsFunction );
    }

    protected TestGraphDatabaseFactory getTestGraphDatabaseFactory()
    {
        return new TestEnterpriseGraphDatabaseFactory();
    }

    protected Consumer<Map<Setting<?>, String>> getSettingsFunction()
    {
        return settings -> {
            settings.put( GraphDatabaseSettings.auth_enabled, "true" );
            settings.put( GraphDatabaseSettings.auth_manager, "enterprise-auth-manager" );
            settings.put( SecuritySettings.internal_authentication_enabled, "true" );
            settings.put( SecuritySettings.internal_authorization_enabled, "true" );
            settings.put( SecuritySettings.ldap_authentication_enabled, "true" );
            settings.put( SecuritySettings.ldap_authorization_enabled, "true" );
            settings.put( SecuritySettings.ldap_server, "0.0.0.0:10389" );
            settings.put( SecuritySettings.ldap_user_dn_template, "cn={0},ou=users,dc=example,dc=com" );
            settings.put( SecuritySettings.ldap_system_username, "uid=admin,ou=system" );
            settings.put( SecuritySettings.ldap_system_password, "secret" );
            settings.put( SecuritySettings.ldap_authorization_use_system_account, "true" );
            settings.put( SecuritySettings.ldap_authorization_user_search_base, "dc=example,dc=com" );
            settings.put( SecuritySettings.ldap_authorization_user_search_filter, "(&(objectClass=*)(uid={0}))" );
            settings.put( SecuritySettings.ldap_authorization_group_membership_attribute_names, "gidnumber" );
            settings.put( SecuritySettings.ldap_authorization_group_to_role_mapping, "500=reader;501=publisher;502=architect;503=admin" );
        };
    }

    public Factory<Connection> cf = (Factory<Connection>) SecureSocketConnection::new;

    public HostnamePort address = new HostnamePort( "localhost:7687" );

    protected Connection client;

    @Test
    @ApplyLdifFiles( "ldap_test_data.ldif" )
    public void shouldBeAbleToLoginWithLdap() throws Throwable
    {
        // When
        client.connect( address )
                .send( TransportTestUtil.acceptedVersions( 1, 0, 0, 0 ) )
                .send( TransportTestUtil.chunk(
                        init( "TestClient/1.1", map( "principal", "neo",
                                "credentials", "abc123", "scheme", "basic" ) ) ) );

        // Then
        assertThat( client, eventuallyRecieves( new byte[]{0, 0, 0, 1} ) );
        assertThat( client, eventuallyRecieves( msgSuccess() ) );
    }

    @Test
    @ApplyLdifFiles( "ldap_test_data.ldif" )
    public void shouldBeAbleToLoginAndAuthorizeReaderWithLdapOnly() throws Throwable
    {
        restartNeo4jServerWithOverriddenSettings( ldapOnlyAuthSettings );

        testAuthWithReaderUser();
    }

    @Test
    @ApplyLdifFiles( "ldap_test_data.ldif" )
    public void shouldBeAbleToLoginAndAuthorizePublisherWithLdapOnly() throws Throwable
    {
        restartNeo4jServerWithOverriddenSettings( ldapOnlyAuthSettings );

        testAuthWithPublisherUser();
    }

    @Test
    @ApplyLdifFiles( "ldap_test_data.ldif" )
    public void shouldBeAbleToLoginAndAuthorizeNoPermissionUserWithLdapOnly() throws Throwable
    {
        restartNeo4jServerWithOverriddenSettings( ldapOnlyAuthSettings );

        testAuthWithNoPermissionUser( "smith" );
    }

    @Test
    @ApplyLdifFiles( "ldap_test_data.ldif" )
    public void shouldBeAbleToLoginAndAuthorizeNoPermissionUserWithLdapOnlyAndNoGroupToRoleMapping() throws Throwable
    {
        restartNeo4jServerWithOverriddenSettings( ldapOnlyAuthSettings.andThen( settings -> {
            settings.put( SecuritySettings.ldap_authorization_group_to_role_mapping, null );
        } ) );

        // User 'neo' has reader role by default, but since we are not passing a group-to-role mapping
        // he should get no permissions
        testAuthWithNoPermissionUser( "neo" );
    }

    @Test
    @ApplyLdifFiles( "ldap_test_data.ldif" )
    public void shouldBeAbleToLoginAndAuthorizeReaderWithUserLdapContext() throws Throwable
    {
        restartNeo4jServerWithOverriddenSettings( ldapOnlyAuthSettings.andThen( settings -> {
            settings.put( SecuritySettings.ldap_authorization_use_system_account, "false" );
        } ) );

        testAuthWithReaderUser();
    }

    @Test
    @ApplyLdifFiles( "ldap_test_data.ldif" )
    public void shouldBeAbleToLoginAndAuthorizePublisherWithUserLdapContext() throws Throwable
    {
        restartNeo4jServerWithOverriddenSettings( ldapOnlyAuthSettings.andThen( settings -> {
            settings.put( SecuritySettings.ldap_authorization_use_system_account, "false" );
        } ) );

        testAuthWithPublisherUser();
    }

    @Test
    @ApplyLdifFiles( "ldap_test_data.ldif" )
    public void shouldBeAbleToLoginAndAuthorizeNoPermissionUserWithUserLdapContext() throws Throwable
    {
        restartNeo4jServerWithOverriddenSettings( ldapOnlyAuthSettings.andThen( settings -> {
            settings.put( SecuritySettings.ldap_authorization_use_system_account, "false" );
        } ) );

        testAuthWithNoPermissionUser( "smith" );
    }

    @Test
    @ApplyLdifFiles( "ldap_test_data.ldif" )
    public void shouldBeAbleToLoginAndAuthorizeNoPermissionUserWithUserLdapContextAndNoGroupToRoleMapping() throws Throwable
    {
        restartNeo4jServerWithOverriddenSettings( ldapOnlyAuthSettings.andThen( settings -> {
            settings.put( SecuritySettings.ldap_authorization_use_system_account, "false" );
            settings.put( SecuritySettings.ldap_authorization_group_to_role_mapping, null );
        } ) );

        // User 'neo' has reader role by default, but since we are not passing a group-to-role mapping
        // he should get no permissions
        testAuthWithNoPermissionUser( "neo" );
    }

    @Test
    @ApplyLdifFiles( "ldap_test_data.ldif" )
    public void shouldBeAbleToLoginWithLdapAndAuthorizeInternally() throws Throwable
    {
        restartNeo4jServerWithOverriddenSettings( settings -> {
            settings.put( SecuritySettings.internal_authentication_enabled, "false" );
            settings.put( SecuritySettings.internal_authorization_enabled, "true" );
            settings.put( SecuritySettings.ldap_authentication_enabled, "true" );
            settings.put( SecuritySettings.ldap_authorization_enabled, "false" );
        } );

        //--------------------------
        // First login as the admin user 'neo4j' and create the internal user 'neo' with role 'reader'
        testCreateReaderUser();

        //--------------------------
        // Then login user 'neo' with LDAP and test that internal authorization gives correct permission
        reconnect();

        testAuthWithReaderUser();
    }

    @Before
    public void setup()
    {
        this.client = cf.newInstance();
    }

    @After
    public void teardown() throws Exception
    {
        if ( client != null )
        {
            client.disconnect();
        }
    }

    private void reconnect() throws Exception
    {
        if ( client != null )
        {
            client.disconnect();
        }
        this.client = cf.newInstance();
    }

    private void testCreateReaderUser() throws Exception
    {
        client.connect( address )
                .send( TransportTestUtil.acceptedVersions( 1, 0, 0, 0 ) )
                .send( TransportTestUtil.chunk(
                        init( "TestClient/1.1", map( "principal", "neo4j",
                                "credentials", "abc123", "scheme", "basic" ) ) ) );

        assertThat( client, eventuallyRecieves( new byte[]{0, 0, 0, 1} ) );
        assertThat( client, eventuallyRecieves( msgSuccess() ) );

        // NOTE: The default user 'neo4j' has password change required, so we have to first change it
        client.send( TransportTestUtil.chunk(
                run( "CALL dbms.changeUserPassword('neo4j', '123') CALL dbms.createUser( 'neo', 'invalid', false ) " +
                     "CALL dbms.addUserToRole( 'neo', 'reader' ) RETURN 0" ),
                pullAll() ) );

        assertThat( client, eventuallyRecieves( msgSuccess() ) );
    }

    private void testAuthWithReaderUser() throws Exception
    {
        // When
        client.connect( address )
                .send( TransportTestUtil.acceptedVersions( 1, 0, 0, 0 ) )
                .send( TransportTestUtil.chunk(
                        init( "TestClient/1.1", map( "principal", "neo",
                                "credentials", "abc123", "scheme", "basic" ) ) ) );

        // Then
        assertThat( client, eventuallyRecieves( new byte[]{0, 0, 0, 1} ) );
        assertThat( client, eventuallyRecieves( msgSuccess() ) );

        // When
        client.send( TransportTestUtil.chunk(
                run( "MATCH (n) RETURN n" ),
                pullAll() ) );

        // Then
        assertThat( client, eventuallyRecieves( msgSuccess(), msgSuccess() ) );

        // When
        client.send( TransportTestUtil.chunk(
                run( "CREATE ()" ),
                pullAll() ) );

        // Then
        assertThat( client, eventuallyRecieves(
                msgFailure( Status.Security.Forbidden,
                        String.format( "Write operations are not allowed for 'neo'." ) ) ) );
    }

    private void testAuthWithPublisherUser() throws Exception
    {
        // When
        client.connect( address )
                .send( TransportTestUtil.acceptedVersions( 1, 0, 0, 0 ) )
                .send( TransportTestUtil.chunk(
                        init( "TestClient/1.1", map( "principal", "tank",
                                "credentials", "abc123", "scheme", "basic" ) ) ) );

        // Then
        assertThat( client, eventuallyRecieves( new byte[]{0, 0, 0, 1} ) );
        assertThat( client, eventuallyRecieves( msgSuccess() ) );

        // When
        client.send( TransportTestUtil.chunk(
                run( "CREATE ()" ),
                pullAll() ) );

        // Then
        assertThat( client, eventuallyRecieves( msgSuccess(), msgSuccess() ) );
    }

    private void testAuthWithNoPermissionUser( String username ) throws Exception
    {
        // When
        client.connect( address )
                .send( TransportTestUtil.acceptedVersions( 1, 0, 0, 0 ) )
                .send( TransportTestUtil.chunk(
                        init( "TestClient/1.1", map( "principal", username,
                                "credentials", "abc123", "scheme", "basic" ) ) ) );

        // Then
        assertThat( client, eventuallyRecieves( new byte[]{0, 0, 0, 1} ) );
        assertThat( client, eventuallyRecieves( msgSuccess() ) );

        // When
        client.send( TransportTestUtil.chunk(
                run( "MATCH (n) RETURN n" ),
                pullAll() ) );

        // Then
        assertThat( client, eventuallyRecieves(
                msgFailure( Status.Security.Forbidden,
                        String.format( "Read operations are not allowed for '%s'.", username ) ) ) );
    }

    private Consumer<Map<Setting<?>,String>> ldapOnlyAuthSettings = settings ->
    {
        settings.put( SecuritySettings.internal_authentication_enabled, "false" );
        settings.put( SecuritySettings.internal_authorization_enabled, "false" );
        settings.put( SecuritySettings.ldap_authentication_enabled, "true" );
        settings.put( SecuritySettings.ldap_authorization_enabled, "true" );
    };
}
