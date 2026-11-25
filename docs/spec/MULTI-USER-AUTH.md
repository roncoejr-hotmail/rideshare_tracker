# Multi-user authentication

A model for APIs that more than one person will call. The Covered-Call Engine is the worked example. The rules below are the part worth copying. Profiles, books, and desktop packaging are not.

The operator procedure for this engine is [ADDING-USERS.md](ADDING-USERS.md).

## The credential is the person

One secret does two jobs. It opens the API, and it selects exactly one tenant. A tenant is a person. Many secrets may be valid at the same time. Each secret belongs to one tenant.

A second person is a new tenant with a new secret. A profile, a role, or a request header under someone else's secret is not a boundary. Any client that presents the operator's secret is the operator, whatever name it sends along.

Inside one tenant, a profile can still mean "live book" or "demo book." That choice never changes which person is calling.

## What the caller sends

Every protected request carries:

```
Authorization: Bearer <secret>
```

There is no second header that switches the person. Health checks may stay open. Everything under the API requires a secret.

## What is stored

Two records:

| Record | Holds | Does not hold |
| --- | --- | --- |
| Tenant | Name, enabled flag, operator flag | The secret |
| Key | Tenant id, SHA-256 of the secret, a label, enabled flag | The secret, a permission |

The hash is SHA-256 over the UTF-8 bytes of the secret, written as lowercase hex. The label is a name such as `desktop` or `agent`. It tells you which client a key was issued for. It does not grant a different scope.

The plaintext exists in two places only: the create response, once, and the client's own config. It is not written to the database, a log, a document, or a chat.

A column left over from a single-key design is not a credential. After migration it contains a marker, `retired:<id>`, and the server never hashes that marker back into a usable key. A temporary placeholder such as `pending-…` is not a secret either.

## The door

On every request, in order:

1. A missing or blank bearer is rejected. No tenant is loaded.
2. Hash the bearer and look up that hash. An unknown hash is rejected before the tenant is loaded, and before any of that tenant's data is read.
3. A disabled key is rejected the same way, before the tenant is loaded.
4. Load the tenant that owns that key. Reject the request when the tenant is disabled, or when the key's tenant id does not match the tenant row.
5. There is no fallback tenant and no fallback key. A failure never opens the operator's data.

Every rejection is the same response. This engine uses `401` and `{"error":"unauthorized - missing or invalid API key"}`. The body does not say which check failed.

A success remembers two facts for the rest of the request: the tenant, and the id of the key that opened the door. Later actions such as rotate and disable need the key id. The principal is the tenant.

## Who may add a person

One tenant is the operator. Only that tenant may issue another tenant.

Issuing creates the tenant, an empty place for that person's data, and that person's first key. The response returns the new secret once. The new tenant is not an operator and cannot issue anyone else. The new tenant cannot read any other tenant's data.

The name is required and short. This engine allows at most 80 characters. A caller who is not the operator receives `403` and `{"error":"forbidden"}`.

## Rotate and disable

Rotation replaces the hash of the key that authenticated the request. Other keys for the same tenant keep working. The new secret is not echoed. A client that still holds the old secret can no longer use that one key. The new secret must be at least 32 characters and must not hash to a key that already exists.

Disabling is an operator action on a key id. Disabling one person's key does not affect any other person's keys. Two disables are refused:

- The key that authenticated this request.
- The last enabled key of an operator tenant.

A disabled key fails the door before any data is read.

## Moving an API that already has one shared secret

When a secret already lives on the tenant row, copy it forward before the new process accepts traffic:

1. Stop the old process and copy the database.
2. For each tenant whose column still holds a real secret, insert one enabled key row with the hash, labeled for the existing client, then replace the column with `retired:<id>`.
3. If no tenant is an operator, mark the lowest tenant id as the operator.
4. Run the same copy again after a brand-new database creates its first tenant. The second run must add nothing, and it must not hash a `retired:` or `pending-` value.

Do the copy in a transaction, from a collaborator that runs before the port opens. An application runner alone is too late: the server is already accepting requests, and a hash-only door would reject the current secret until the copy finished.

Once the marker is written, the previous build can no longer authenticate, because it still reads the column. The way back is the database copy, then the old build. Starting the old build against the migrated database locks everyone out.

The existing client keeps working. It still sends the same secret. Only the server's lookup changed.

## Checklist for the next API

- A person is a tenant. A secret selects that tenant and nothing else.
- Many secrets may be valid. Each secret has one tenant.
- Store the SHA-256 only. Return a new plaintext once, at creation.
- Unknown and disabled secrets fail before any of that person's data is read.
- Nothing falls back to the operator.
- Only the operator may create a tenant. A new tenant is not an operator.
- Rotation changes the presented key only.
- The operator can disable another key, and cannot disable the key they are using or their own last key.
- A leftover plaintext column is hashed once at startup and then replaced with a non-secret marker.
- Clients keep their own config. The distributed app does not contain a secret.

