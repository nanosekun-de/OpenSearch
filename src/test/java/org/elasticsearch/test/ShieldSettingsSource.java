/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.test;

import com.carrotsearch.randomizedtesting.RandomizedTest;
import com.google.common.base.Charsets;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.client.support.Headers;
import org.elasticsearch.common.io.Streams;
import org.elasticsearch.common.os.OsUtils;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.shield.ShieldPlugin;
import org.elasticsearch.shield.authc.esusers.ESUsersRealm;
import org.elasticsearch.shield.authc.support.SecuredString;
import org.elasticsearch.shield.authc.support.UsernamePasswordToken;
import org.elasticsearch.shield.signature.InternalSignatureService;
import org.elasticsearch.test.discovery.ClusterDiscoveryConfiguration;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;

import static org.elasticsearch.common.settings.ImmutableSettings.settingsBuilder;
import static org.elasticsearch.shield.authc.support.UsernamePasswordToken.basicAuthHeaderValue;

/**
 * {@link org.elasticsearch.test.SettingsSource} subclass that allows to set all needed settings for shield.
 * Unicast discovery is configured through {@link org.elasticsearch.test.discovery.ClusterDiscoveryConfiguration.UnicastZen},
 * also shield is installed with all the needed configuration and files.
 * To avoid conflicts, every cluster should have its own instance of this class as some configuration files need to be created.
 */
public class ShieldSettingsSource extends ClusterDiscoveryConfiguration.UnicastZen {

    public static final String DEFAULT_USER_NAME = "test_user";
    public static final String DEFAULT_PASSWORD = "changeme";
    public static final String DEFAULT_ROLE = "user";

    public static final String DEFAULT_TRANSPORT_CLIENT_ROLE = "trans_client_user";
    public static final String DEFAULT_TRANSPORT_CLIENT_USER_NAME = "test_trans_client_user";

    public static final String CONFIG_STANDARD_USER =
            DEFAULT_USER_NAME + ":{plain}" + DEFAULT_PASSWORD + "\n" +
            DEFAULT_TRANSPORT_CLIENT_USER_NAME + ":{plain}" + DEFAULT_PASSWORD + "\n";

    public static final String CONFIG_STANDARD_USER_ROLES =
            DEFAULT_ROLE + ":" + DEFAULT_USER_NAME + "," + DEFAULT_TRANSPORT_CLIENT_USER_NAME + "\n" +
            DEFAULT_TRANSPORT_CLIENT_ROLE + ":" + DEFAULT_TRANSPORT_CLIENT_USER_NAME+ "\n";

    public static final String CONFIG_ROLE_ALLOW_ALL =
            DEFAULT_ROLE + ":\n" +
                    "  cluster: ALL\n" +
                    "  indices:\n" +
                    "    '*': ALL\n" +
            DEFAULT_TRANSPORT_CLIENT_ROLE + ":\n" +
                    "  cluster:\n" +
                    "    - cluster:monitor/nodes/info\n" +
                    "    - cluster:monitor/state";

    private final File parentFolder;
    private final String subfolderPrefix;
    private final byte[] systemKey;

    /**
     * Creates a new {@link org.elasticsearch.test.SettingsSource} for the shield configuration.
     *
     * @param numOfNodes the number of nodes for proper unicast configuration (can be more than actually available)
     * @param parentFolder the parent folder that will contain all of the configuration files that need to be created
     * @param scope the scope of the test that is requiring an instance of ShieldSettingsSource
     */
    public ShieldSettingsSource(int numOfNodes, File parentFolder, ElasticsearchIntegrationTest.Scope scope) {
        super(numOfNodes, ImmutableSettings.builder()
                .put("node.mode", "network")
                .put("plugin.types", ShieldPlugin.class.getName())
                .put("plugins.load_classpath_plugins", false)
                .build(),
                scope);
        this.systemKey = generateKey();
        this.parentFolder = parentFolder;
        this.subfolderPrefix = scope.name();
    }

    @Override
    public Settings node(int nodeOrdinal) {
        File folder = createFolder(parentFolder, subfolderPrefix + "-" + nodeOrdinal);
        ImmutableSettings.Builder builder = ImmutableSettings.builder().put(super.node(nodeOrdinal))
                .put("shield.audit.enabled", RandomizedTest.randomBoolean())
                .put(InternalSignatureService.FILE_SETTING, writeFile(folder, "system_key", systemKey))
                .put("shield.authc.realms.esusers.type", ESUsersRealm.TYPE)
                .put("shield.authc.realms.esusers.files.users", writeFile(folder, "users", configUsers()))
                .put("shield.authc.realms.esusers.files.users_roles", writeFile(folder, "users_roles", configUsersRoles()))
                .put("shield.authz.store.files.roles", writeFile(folder, "roles.yml", configRoles()))
                .put(getNodeSSLSettings());

        if (OsUtils.MAC) {
            builder.put("network.host", RandomizedTest.randomBoolean() ? "127.0.0.1" : "::1");
        }

        setUser(builder, nodeClientUsername(), nodeClientPassword());

        return builder.build();
    }

    protected String configUsers() {
        return CONFIG_STANDARD_USER;
    }

    protected String configUsersRoles() {
        return CONFIG_STANDARD_USER_ROLES;
    }

    protected String configRoles() {
        return CONFIG_ROLE_ALLOW_ALL;
    }

    protected String nodeClientUsername() {
        return DEFAULT_USER_NAME;
    }

    protected SecuredString nodeClientPassword() {
        return new SecuredString(DEFAULT_PASSWORD.toCharArray());
    }

    protected String transportClientUsername() {
        return DEFAULT_TRANSPORT_CLIENT_USER_NAME;
    }

    protected SecuredString transportClientPassword() {
        return new SecuredString(DEFAULT_PASSWORD.toCharArray());
    }

    @Override
    public Settings transportClient() {
        ImmutableSettings.Builder builder = ImmutableSettings.builder().put(super.transportClient())
                .put(getClientSSLSettings());
        setUser(builder, transportClientUsername(), transportClientPassword());
        return builder.build();
    }

    private void setUser(ImmutableSettings.Builder builder, String username, SecuredString password) {
        if (RandomizedTest.randomBoolean()) {
            builder.put(Headers.PREFIX + "." + UsernamePasswordToken.BASIC_AUTH_HEADER, basicAuthHeaderValue(username, password));
        } else {
            builder.put("shield.user", username + ":" + new String(password.internalChars()));
        }
    }

    private static File createFolder(File parent, String name) {
        File createdFolder = new File(parent, name);
        if (createdFolder.exists()) {
            if (!createdFolder.delete()) {
                throw new RuntimeException("Could not delete existing temporary folder: " + createdFolder.getAbsolutePath());
            }
        }
        if (!createdFolder.mkdir()) {
            throw new RuntimeException("Could not create temporary folder: " + createdFolder.getAbsolutePath());
        }
        return createdFolder;
    }

    private static String writeFile(File folder, String name, byte[] content) {
        Path file = folder.toPath().resolve(name);
        try {
            Streams.copy(content, file.toFile());
        } catch (IOException e) {
            throw new ElasticsearchException("Error writing file in test", e);
        }
        return file.toFile().getAbsolutePath();
    }

    private static String writeFile(File folder, String name, String content) {
        return writeFile(folder, name, content.getBytes(Charsets.UTF_8));
    }

    private static byte[] generateKey() {
        try {
            return InternalSignatureService.generateKey();
        } catch (Exception e) {
            throw new ElasticsearchException("exception while generating the system key", e);
        }
    }

    private static Settings getNodeSSLSettings() {
        return getSSLSettingsForStore("/org/elasticsearch/shield/transport/ssl/certs/simple/testnode.jks", "testnode");
    }

    private static Settings getClientSSLSettings() {
        return getSSLSettingsForStore("/org/elasticsearch/shield/transport/ssl/certs/simple/testclient.jks", "testclient");
    }

    public static Settings getSSLSettingsForStore(String resourcePathToStore, String password) {
        File store;
        try {
            store = new File(ShieldSettingsSource.class.getResource(resourcePathToStore).toURI());
        } catch (URISyntaxException e) {
            throw new ElasticsearchException("exception while reading the store", e);
        }

        if (!store.exists()) {
            throw new ElasticsearchException("store path doesn't exist");
        }

        ImmutableSettings.Builder builder = settingsBuilder()
                .put("shield.ssl.keystore.path", store.getPath())
                .put("shield.ssl.keystore.password", password)
                .put("shield.transport.ssl", true)
                .put("shield.http.ssl", false);

        if (RandomizedTest.randomBoolean()) {
            builder.put("shield.ssl.truststore.path", store.getPath())
                    .put("shield.ssl.truststore.password", password);
        }
        return builder.build();
    }
}
