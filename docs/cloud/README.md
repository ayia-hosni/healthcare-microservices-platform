# ☁️ Cloud Architecture

The platform's cloud architecture is documented per provider:

| Provider | Status | Doc |
| --- | --- | --- |
| **Azure** | ✅ Real, provisioned (`infra/terraform/`) | [`azure/README.md`](azure/README.md) |
| **AWS** | 🏗️ Designed, not built — no Terraform provider exists for it | [`aws/README.md`](aws/README.md) |

Both documents describe the **same application architecture** — the same domain-oriented
microservices, database-per-service boundaries, and event contracts — mapped onto a different
provider's managed services. The business/application layer doesn't change between them; only
the infrastructure layer does. See [`aws/README.md`](aws/README.md#azure--aws-service-mapping)
for the concern-by-concern mapping between the two.

For an itemized, cross-cutting view of what's actually implemented versus designed versus
planned across the whole platform (not just cloud infrastructure), see
[`../../PROGRESS.md`](../../PROGRESS.md).
