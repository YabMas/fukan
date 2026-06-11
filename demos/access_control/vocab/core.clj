(ns demos.access-control.vocab.core
  "A role-based access-control (RBAC) vocabulary, built directly on defstructure.
   A Permission is an (action, resource) pair; a Role grants permissions and may
   inherit other roles, so a role's EFFECTIVE permissions are its own grants plus
   everything its ancestors grant (transitively). Well-formedness — an acyclic
   role hierarchy and separation of duties — is expressed as recursive free laws
   over the inheritance graph.

   Modelling choices worth noting — each is a finding about the core:

   - IDENTITY-BY-COMPOSITION has no expression. A Permission *is* its (action,
     resource) pair — (read, Report) is the same permission no matter what it's
     called. But the core identifies a node by its name + structure tag, not by
     its slot composition, so two differently-named Permissions over the same
     (action, resource) are two distinct nodes, and nothing forbids that. Value
     identity (a node that IS its components, like a tuple) vs entity identity
     (a named thing with components) is a distinction the core can't yet draw —
     a new gap, adjacent to but distinct from the scalar gap.

   - SEPARATION OF DUTIES as DATA, not a hardcoded rule. Which permissions are
     toxic together is itself modelled — a Permission :conflicts-with other
     permissions — so the SoD law is generic: it fires for ANY role whose
     effective permissions include both ends of a :conflicts-with edge. The
     forbidden-CO-OCCURRENCE shape (a law that two things must not appear
     together) is new to the corpus — earlier demos had reachability,
     cardinality, acyclicity, termination, never a mutual exclusion.

   - DIRECTED relations model an UNDIRECTED fact. `:conflicts-with` is symmetric
     (if A conflicts with B then B conflicts with A), but the core's relations are
     directed. One direction suffices here — the SoD law binds both permissions
     from the role's grants and only needs the conflict edge to exist in either
     declared direction — but genuinely symmetric relations (sibling, peer) have
     no native form; you declare one direction and interpret it as symmetric.

   - The role hierarchy is a DAG (acyclic by law) but diamonds are fine (author
     and approver both inherit viewer). `grants*` — effective permission — is a
     NEW recursion shape: the transitive closure of one relation (:inherits)
     composed with a final hop along a DIFFERENT relation (:grants). Like every
     recursive law here it INLINES its step (a recursive rule may not call a
     helper rule — datascript diverges on cyclic data otherwise); set semantics
     make it terminate even when the inheritance graph happens to contain a cycle."
  (:require [fukan.canvas.core.structure :refer [defstructure]]))

(defstructure Action
  "An operation that can be performed on a resource — read, submit, approve, ….")

(defstructure Resource
  "A protected thing access is controlled over — a Report, a Budget, ….")

(defstructure Permission
  "The right to perform one action on one resource. `:conflicts-with` names other
   permissions that must not be held by the same role (separation of duties)."
  {:action         Action
   :resource       Resource
   :conflicts-with [:* Permission]})

(defstructure Role
  "A role: the permissions it directly grants, plus the roles it inherits (whose
   permissions it also effectively holds)."
  {:grants   [:* Permission]
   :inherits [:* Role]}

  ;; Acyclic hierarchy: a role may not (transitively) inherit itself. `inh*` is
  ;; transitive inheritance over the DIRECT :inherits relation, step INLINED.
  (law "no cycle in the role inheritance hierarchy"
    :rules '[[(inh* ?a ?b)
              [?r :rel/from ?a] [?r :rel/kind :inherits] [?r :rel/to ?b]]
             [(inh* ?a ?b)
              [?r :rel/from ?a] [?r :rel/kind :inherits] [?r :rel/to ?m]
              (inh* ?m ?b)]]
    :scope ::Role
    :offenders '[?role]
    :where '[(inh* ?role ?role)])

  ;; Separation of duties: no role's EFFECTIVE permissions include both ends of a
  ;; :conflicts-with edge. `grants*` is effective permission — direct grants, or a
  ;; grant by any (transitively) inherited role; the :inherits step is INLINED
  ;; into the recursive rule.
  (law "no role holds two conflicting permissions (separation of duties)"
    :rules '[[(grants* ?role ?p)
              [?rg :rel/from ?role] [?rg :rel/kind :grants] [?rg :rel/to ?p]]
             [(grants* ?role ?p)
              [?ri :rel/from ?role] [?ri :rel/kind :inherits] [?ri :rel/to ?mid]
              (grants* ?mid ?p)]]
    :scope ::Role
    :offenders '[?role ?p1 ?p2]
    :where '[(grants* ?role ?p1)
             (grants* ?role ?p2)
             [?rc :rel/from ?p1] [?rc :rel/kind :conflicts-with] [?rc :rel/to ?p2]]))
