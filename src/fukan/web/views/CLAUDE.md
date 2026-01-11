# View Spec Implementation Guide

This module implements `view-spec.md`. Read it first.

## Core Principle: Match the Spec's Abstraction Level

The view-spec describes behavior in terms of **containers** and **children** - not folders, namespaces, or vars. Your code should do the same.

**Wrong:** Type-specific branches for spec-level behavior
```clojure
(case child-kind
  :var (compute-namespace-view ...)    ;; duplicates logic
  :namespace (compute-folder-view ...)) ;; with type-specific details
```

**Right:** One implementation that operates on containers generically
```clojure
(compute-container-view-impl model entity-id children-ids)
;; Type-specific logic only where the spec requires it
```

### Why This Matters

When spec behavior is split across type-specific branches:
- The spec can be violated in one branch while working in another
- Bugs hide in edge cases of specific types
- Changes must be made in multiple places

### How to Apply

1. **Read the spec rule** - e.g., "no edges between container and children"
2. **Notice if it mentions types** - if not, your code shouldn't branch on types
3. **Write one implementation** that expresses the rule directly
4. **Test the invariant generically** - verify across all types, not per-type

### Testing Invariants

Tests should verify spec rules hold regardless of type:

```clojure
;; Good: tests the spec invariant generically
(deftest no-container-child-edges
  (doseq [view-id ["folder:x" "ns:y" "ns:z"]]
    (is (not (has-container-child-edge? (compute-graph view-id))))))

;; Weak: only tests one type
(deftest no-folder-child-edges ...)
```
