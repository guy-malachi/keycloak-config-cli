/*
 * Copyright 2019-2020 adorsys GmbH & Co. KG @ https://adorsys.de
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package de.adorsys.keycloak.config.service;

import de.adorsys.keycloak.config.model.RealmImport;
import de.adorsys.keycloak.config.properties.ImportConfigProperties;
import de.adorsys.keycloak.config.repository.RealmRepository;
import de.adorsys.keycloak.config.service.checksum.ChecksumService;
import de.adorsys.keycloak.config.service.lock.LockService;
import de.adorsys.keycloak.config.util.CloneUtils;
import org.keycloak.representations.idm.RealmRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class RealmImportService {
    private static final Logger logger = LoggerFactory.getLogger(RealmImportService.class);

    private final String[] ignoredPropertiesForCreation = new String[]{
            "users",
            "groups",
            "browserFlow",
            "directGrantFlow",
            "clientAuthenticationFlow",
            "dockerAuthenticationFlow",
            "registrationFlow",
            "resetCredentialsFlow",
            "components",
            "authenticationFlows"
    };

    private final String[] ignoredPropertiesForUpdate = new String[]{
            "clients",
            "roles",
            "users",
            "groups",
            "identityProviders",
            "browserFlow",
            "directGrantFlow",
            "clientAuthenticationFlow",
            "dockerAuthenticationFlow",
            "registrationFlow",
            "resetCredentialsFlow",
            "components",
            "authenticationFlows",
            "requiredActions"
    };

    private final String[] patchingPropertiesForFlowImport = new String[]{
            "browserFlow",
            "directGrantFlow",
            "clientAuthenticationFlow",
            "dockerAuthenticationFlow",
            "registrationFlow",
            "resetCredentialsFlow",
    };

    private final KeycloakProvider keycloakProvider;
    private final RealmRepository realmRepository;

    private final UserImportService userImportService;
    private final RoleImportService roleImportService;
    private final ClientImportService clientImportService;
    private final ClientScopeImportService clientScopeImportService;
    private final GroupImportService groupImportService;
    private final ComponentImportService componentImportService;
    private final AuthenticationFlowsImportService authenticationFlowsImportService;
    private final AuthenticatorConfigImportService authenticatorConfigImportService;
    private final RequiredActionsImportService requiredActionsImportService;
    private final CustomImportService customImportService;
    private final ScopeMappingImportService scopeMappingImportService;
    private final IdentityProviderImportService identityProviderImportService;

    private final ImportConfigProperties importProperties;

    private final LockService lockService;
    private final ChecksumService checksumService;

    @Autowired
    public RealmImportService(
            ImportConfigProperties importProperties,
            KeycloakProvider keycloakProvider,
            RealmRepository realmRepository,
            UserImportService userImportService,
            RoleImportService roleImportService,
            ClientImportService clientImportService,
            GroupImportService groupImportService,
            ClientScopeImportService clientScopeImportService,
            ComponentImportService componentImportService,
            AuthenticationFlowsImportService authenticationFlowsImportService,
            AuthenticatorConfigImportService authenticatorConfigImportService,
            RequiredActionsImportService requiredActionsImportService,
            CustomImportService customImportService,
            ScopeMappingImportService scopeMappingImportService,
            IdentityProviderImportService identityProviderImportService,
            LockService lockService,
            ChecksumService checksumService
    ) {
        this.importProperties = importProperties;
        this.keycloakProvider = keycloakProvider;
        this.realmRepository = realmRepository;
        this.userImportService = userImportService;
        this.roleImportService = roleImportService;
        this.clientImportService = clientImportService;
        this.groupImportService = groupImportService;
        this.clientScopeImportService = clientScopeImportService;
        this.componentImportService = componentImportService;
        this.authenticationFlowsImportService = authenticationFlowsImportService;
        this.authenticatorConfigImportService = authenticatorConfigImportService;
        this.requiredActionsImportService = requiredActionsImportService;
        this.customImportService = customImportService;
        this.scopeMappingImportService = scopeMappingImportService;
        this.identityProviderImportService = identityProviderImportService;
        this.lockService = lockService;
        this.checksumService = checksumService;
    }

    public void doImport(RealmImport realmImport) {
        boolean realmExists = realmRepository.exists(realmImport.getRealm());

        try {
            if (realmExists) {
                updateRealmIfNecessary(realmImport);
            } else {
                createRealm(realmImport);
            }
        } finally {
            lockService.releaseLock(realmImport);
        }

        keycloakProvider.close();
    }

    private void createRealm(RealmImport realmImport) {
        lockService.getLock(realmImport);

        logger.debug("Creating realm '{}' ...", realmImport.getRealm());

        RealmRepresentation realmForCreation = CloneUtils.deepClone(realmImport, RealmRepresentation.class, ignoredPropertiesForCreation);
        realmRepository.create(realmForCreation);

        userImportService.doImport(realmImport);
        groupImportService.importGroups(realmImport);
        authenticationFlowsImportService.doImport(realmImport);
        setupFlows(realmImport);
        componentImportService.doImport(realmImport);
        customImportService.doImport(realmImport);
        checksumService.doImport(realmImport);
    }

    private void updateRealmIfNecessary(RealmImport realmImport) {
        if (Boolean.TRUE.equals(importProperties.getForce()) || checksumService.hasToBeUpdated(realmImport)) {
            updateRealm(realmImport);
        } else {
            logger.debug(
                    "No need to update realm '{}', import checksum same: '{}'",
                    realmImport.getRealm(),
                    realmImport.getChecksum()
            );
        }
    }

    private void updateRealm(RealmImport realmImport) {
        lockService.getLock(realmImport);

        logger.debug("Updating realm '{}'...", realmImport.getRealm());

        RealmRepresentation realmToUpdate = CloneUtils.deepClone(realmImport, RealmRepresentation.class, ignoredPropertiesForUpdate);
        realmRepository.update(realmToUpdate);

        clientImportService.doImport(realmImport);
        roleImportService.doImport(realmImport);
        groupImportService.importGroups(realmImport);
        clientScopeImportService.importClientScopes(realmImport);
        userImportService.doImport(realmImport);
        requiredActionsImportService.doImport(realmImport);
        authenticationFlowsImportService.doImport(realmImport);
        authenticatorConfigImportService.doImport(realmImport);
        setupFlows(realmImport);
        componentImportService.doImport(realmImport);
        scopeMappingImportService.doImport(realmImport);
        identityProviderImportService.doImport(realmImport);
        customImportService.doImport(realmImport);

        checksumService.doImport(realmImport);
    }

    private void setupFlows(RealmImport realmImport) {
        RealmRepresentation existingRealm = realmRepository.get(realmImport.getRealm());
        RealmRepresentation realmToUpdate = CloneUtils.deepPatchFieldsOnly(existingRealm, realmImport, patchingPropertiesForFlowImport);

        realmRepository.update(realmToUpdate);
    }
}
