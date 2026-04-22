# ADR-0003: In-house rule DSL over Drools or a generic expression engine

- **Status:** Accepted
- **Date:** 2025-11-12
- **Deciders:** @core-team

## Context

The rule engine evaluates human-authored rules against enriched transactions. Rules are authored by fraud analysts (not engineers) and must be:

1. **Readable** by non-engineers — a fraud analyst should be able to review and modify rules.
2. **Composable** — `(high_velocity AND new_device) OR (large_amount AND foreign_country)`.
3. **Deterministic and pure** — rules must not make I/O calls; they must return the same verdict for the same input.
4. **Fast** — we evaluate all rules for every transaction; evaluation latency matters.
5. **Auditable** — the system must record which rules triggered, with their values, for every decision.
6. **Version-controlled** — rule changes go through PR review, not a UI.

## Decision

We will build a **small, domain-specific YAML-based rule DSL** evaluated by a custom in-house engine in `rule-engine`. Rules live in `services/rule-engine/src/main/resources/rules.yaml` and are loaded at service startup.

A rule looks like:

```yaml
- id: high_velocity_new_device
  description: "Many transactions in short window from a device we haven't seen"
  verdict: REVIEW
  when:
    all_of:
      - velocity_1m: { gt: 5 }
      - is_new_device: true
```

## Alternatives considered

### Drools

**Pros:** battle-tested, rich feature set (forward chaining, agenda, truth maintenance), well-known in Java enterprise.

**Cons:**
- Heavy: pulls in a large dependency tree and significant JVM memory footprint.
- Rule authoring uses a Java-like syntax (DRL) that analysts find intimidating.
- Forward-chaining adds complexity we don't need — our rules are stateless classification.
- Debugging Drools rule execution is notoriously hard; "why did this rule not fire?" is a common question.
- Our purity invariant (ADR → pure functions) is not naturally enforced.

**Rejected because:** ~5% of Drools' feature set maps to ~95% of our needs. Paying the complexity cost for unused capability is a bad trade.

### Open Policy Agent (OPA) with Rego

**Pros:** purpose-built for policy evaluation, declarative, widely adopted.

**Cons:**
- Separate process to operate (either sidecar or HTTP service).
- Rego is its own learning curve; analysts would find it harder than YAML.
- Overkill — Rego is designed for complex multi-input policy decisions; ours are single-input.

**Rejected because:** operational and learning overhead outweigh benefits for our use case. Revisit if rules grow to need cross-resource reasoning.

### SpEL (Spring Expression Language) or MVEL

**Pros:** embedded in Spring ecosystem, no additional dependencies, flexible.

**Cons:**
- Both allow arbitrary method invocation — our purity invariant would be enforced by convention, not by the engine.
- Performance is acceptable but not great at high throughput.
- Analysts find the syntax (`transaction.amount > 500 && transaction.isInternational`) readable, but editing is error-prone without an IDE.

**Rejected because:** the escape hatches compromise the purity invariant. A DSL that literally cannot call external code is safer.

### JavaScript/QuickJS sandboxed evaluation

**Pros:** familiar syntax, truly sandboxable.

**Cons:**
- Embedding a JS runtime in a JVM service is awkward and slow.
- Still requires us to define and document a safe API surface — similar effort to defining a DSL from scratch.

**Rejected because:** no meaningful advantage over a purpose-built DSL and real operational downsides.

### Hand-authored Java classes, one per rule

**Pros:** maximum type safety, zero DSL.

**Cons:**
- Non-engineers cannot author rules.
- Every rule change requires a service rebuild and redeploy.
- Version control is fine, but the authoring UX for analysts is terrible.

**Rejected because:** the 80th percentile rule author is a fraud analyst, not a Java engineer.

## Consequences

### Easier

- **Purity is enforced at the evaluator level.** The DSL has no syntax for I/O; pure functions are the only authorable thing.
- **Audit logs are trivial.** Every rule evaluation produces a `RuleTrace` record naming the rule and the operand values.
- **Analyst authoring is a reality.** Rules are YAML — review happens in PRs, changes are obvious in diffs.
- **Testing rules is straightforward.** Each rule is a pure function over a known struct; unit tests are one-liners.
- **Performance is predictable** — no rule can block on I/O.

### Harder

- **We own the DSL.** Syntax extensions, evaluation bugs, better error messages — all our problem.
- **The DSL can ossify.** Analysts will want features we didn't plan for (time-of-day conditions, cross-transaction references). We commit to evolving the DSL deliberately via ADRs, not ad hoc.
- **No rich tooling.** Drools has visualizers, we don't. If rule count exceeds ~200, we'll likely need to build basic tooling (a rule linter, a rule-coverage report).

### New risks

- **DSL grammar creep.** Easy to keep adding operators until the DSL becomes a programming language. Mitigated by requiring an ADR for new operators beyond the initial set.
- **Silent rule-ordering bugs.** If analysts assume ordering matters (it doesn't — we evaluate all rules), they may write subtly wrong rules. Mitigated by documentation and rule-authoring guidelines.

## Initial supported operators

```
# Comparisons
eq, neq, gt, gte, lt, lte

# Set membership
in, not_in

# Boolean composition
all_of, any_of, not

# Literal shortcuts
<field>: true / false / <number> / <string>   # sugar for eq
```

Extensions beyond this set require an ADR.

## References

- [Drools documentation](https://docs.drools.org/)
- [Open Policy Agent](https://www.openpolicyagent.org/)
- Internal doc: `docs/rule-authoring-guide.md` (to be written)
