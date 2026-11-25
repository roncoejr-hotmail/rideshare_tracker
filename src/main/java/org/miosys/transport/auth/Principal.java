package org.miosys.transport.auth;

/** The authenticated caller: its tenant, the key that opened the door, operator flag. */
public record Principal(long tenantId, long keyId, boolean operator) {}
