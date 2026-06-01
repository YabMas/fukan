(ns demos.type-system.vocab.core
  "A nominal type system with subtyping, built directly on defstructure. A Type
   may declare record Fields, may name supertypes (:subtype-of), and may be sealed
   (a leaf-Bool flag). Well-formedness — an acyclic subtype lattice, width
   subtyping, sealed-type protection — is expressed as recursive + value-bearing
   laws. This is the corpus's capstone: the demo we earmarked to PROBE
   value-identity (#5) now that leaf scalars (#4a) have landed.

   Modelling choices worth noting — each is a finding about the core:

   - SUBTYPING IS ORDINARY DATA, not a privileged mechanism. `:subtype-of` is a
     plain relation between Type nodes — structurally identical to access-control's
     `Role :inherits Role`. This confirms the retired-refinement conclusion: a
     subtype lattice (and OOP inheritance, and method resolution) needs NO special
     core support; a relation + recursive laws suffice. The thing the type-system
     demo was once imagined to 'force' (privileged refinement) is a non-need.

   - LEAF SCALARS EARN THEIR KEEP IN LAWS. `:sealed?` (a Bool value slot) drives a
     structural law ('nothing may subtype a sealed type'); `:fname` (a String value
     slot) is matched by the width-subtyping law. So the leaf-scalar primitive is
     exercised beyond ER's mere storage — values now participate in datalog laws.

   - VALUE-IDENTITY (#5) IS LIVE AND UNADDRESSED — exactly as the gate predicted.
     Each record type gets its OWN Field nodes: Point's `x` (handle `px`, fname
     \"x\", type Int) and Point3D's `x` (handle `qx`, fname \"x\", type Int) are
     DISTINCT nodes despite being the same field structurally. The substrate
     identifies nodes by name/uuid, never by composition — so structurally-equal
     fields (and structurally-equal record types, `{x:Int,y:Int}` vs another) can't
     be equated. The width-subtyping law has to match fields by `:val/fname` STRING
     EQUALITY precisely because the two `x` fields aren't one identity. Laws keep
     reaching for name/value matching as a workaround for the missing value-identity.

   - Cycles in the lattice are authorable (two-pass resolution); the `sub*`
     reachability recursion is INLINED (a recursive rule may not call a helper rule
     — datascript diverges on cyclic data otherwise), so it terminates on a cyclic
     lattice and the no-cycle law catches it."
  (:require [fukan.canvas.core.structure :refer [defstructure]]))

(defstructure Field
  "A named, typed member of a record type. `:fname` is the field's name — a leaf
   VALUE, distinct from the Field node's unique handle. Two record types that share
   a field name get two distinct Field nodes with the same `:fname` (see the
   value-identity note in the namespace docstring)."
  (slot :fname (one :String))
  (slot :type  (one Type)))

(defstructure Type
  "A type in a nominal subtyping lattice. It may declare record :fields, name its
   direct supertypes via :subtype-of (transitive, must be acyclic), and be :sealed?
   (no type may then subtype it)."
  (slot :field      (many Field))
  (slot :subtype-of (many Type))
  (slot :sealed?    (optional :Bool))

  ;; Acyclic lattice: no type (transitively) subtypes itself. `sub*` is transitive
  ;; subtyping over the DIRECT :subtype-of relation, step INLINED.
  (law "no cycle in the subtype lattice"
    :rules '[[(sub* ?a ?b)
              [?r :rel/from ?a] [?r :rel/kind :subtype-of] [?r :rel/to ?b]]
             [(sub* ?a ?b)
              [?r :rel/from ?a] [?r :rel/kind :subtype-of] [?r :rel/to ?m]
              (sub* ?m ?b)]]
    :scope :Type
    :offenders '[?t]
    :where '[(sub* ?t ?t)])

  ;; Width subtyping (Liskov for records): a subtype must declare every field NAME
  ;; its supertypes declare. Matched by `:val/fname` string equality — a workaround
  ;; for the absence of value-identity on Field nodes (see the namespace docstring).
  (law "a subtype must declare every field name of its supertypes"
    :scope :Type
    :offenders '[?t ?fname]
    :where '[[?r :rel/from ?t]    [?r :rel/kind :subtype-of] [?r :rel/to ?s]
             [?sr :rel/from ?s]   [?sr :rel/kind :field]      [?sr :rel/to ?sf]
             [?sf :val/fname ?fname]
             (not-join [?t ?fname]
               [?tr :rel/from ?t] [?tr :rel/kind :field]      [?tr :rel/to ?tf]
               [?tf :val/fname ?fname])])

  ;; Sealed-type protection: nothing may subtype a type whose :sealed? value is
  ;; true. A leaf-Bool value slot driving a structural law.
  (law "nothing may be a subtype of a sealed type"
    :scope :Type
    :offenders '[?t ?s]
    :where '[[?r :rel/from ?t] [?r :rel/kind :subtype-of] [?r :rel/to ?s]
             [?s :val/sealed? true]]))
