package org.miosys.transport.store;

import java.util.List;
import java.util.Optional;

/** Operator-only tenant and key administration, plus the open bootstrap route. */
public interface TenantAdmin {

    record IssuedTenant(
            long tenantId, String name, boolean operator, long keyId, String keyLabel, String apiKey) {}

    record IssuedKey(long keyId, long tenantId, String label, String apiKey) {}

    record TenantView(
            long tenantId,
            String name,
            boolean operator,
            boolean enabled,
            String createdAt,
            String updatedAt,
            List<KeyView> keys) {}

    record KeyView(
            long keyId, long tenantId, String label, boolean enabled, String createdAt, String updatedAt) {}

    /** Creates the first tenant as operator. Throws NotFound unless the tenant table is empty. */
    IssuedTenant bootstrap(String name, String keyLabel);

    IssuedTenant createTenant(String name, String keyLabel);

    List<TenantView> tenants();

    TenantView updateTenant(long tenantId, Optional<String> name, Optional<Boolean> enabled);

    IssuedKey issueKey(long tenantId, String label);

    KeyView setKeyEnabled(long keyId, boolean enabled);
}
