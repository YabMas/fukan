(ns fukan.common.vocab.code.band
  "The `Band` element — a stratum of a codebase, claimed by NAMESPACE PREFIX and checked against
   the EXTRACTED call graph.

   The sibling of `Subsystem`, and the difference is the evidence. A Subsystem clusters authored
   Modules and checks its `:may-depend` DAG against `module-depends`, which is built from authored
   `:delegates` — so it says nothing until a region is modelled operation by operation. A Band
   claims namespaces by the prefix of their name and checks the same shape of declaration against
   `ns-depends`, which extraction produces the moment it runs. Same declaration, different
   evidence — and the second one needs no authoring at all, which is what makes a large existing
   codebase declarable in an afternoon rather than an adoption project.

   ⚠ THE ONE PLACE THIS TIER REACHES A LANGUAGE. `Ns` is a Clojure fact sort, and this file names
   it — by FULL TAG KEYWORD, the documented spelling for a namespace deliberately not required, so
   the coupling is at the DATA level and not the compile level. `ns-depends` and the rest reach it
   the same way, through datalog injection. A project with a different extractor loads this vocab,
   mints no `Ns` nodes, and gets laws that are vacuous rather than laws that fail — which is the
   right failure, but it is still a language leaking into a tier that claims to be neutral. The
   honest fix is an extractor-neutral CODE-UNIT sort for every language to populate; inventing one
   before a second extractor exists would be designing a middle layer ahead of its case, so this
   carries the debt in the open instead. A second extractor is the trigger.

   The membership relation is DERIVED, never authored: a namespace's band is readable from its own
   name and so cannot drift from the tree. That is the whole trade — you give up the freedom to
   put a namespace anywhere, and you get a design that no amount of moving files can quietly
   falsify."
  (:require [fukan.canvas.core.structure :as s :refer [defstructure]]))

(defn ^:export read-prefix
  "A bare string in a `:prefix` vector → `NsPrefix` clauses, so a band authors its prefixes as
   plain strings rather than as constructor calls."
  [v]
  [(list 'value v)])

(defstructure ^:value NsPrefix
  "One namespace prefix a Band claims. A `^:value` structure because a slot holding a repeated
   LEAF has no cardinality in the kernel — a scalar slot is one or optional — so a leaf that
   repeats is modelled as a content-deduped node."
  {:value :string}
  (reader read-prefix))

;; `in-band` is the membership relation, derived from the namespace's own name. It is declared
;; here rather than beside `Ns` because it is Band's semantics, not the extractor's: what a Band
;; MEANS is the set of namespaces under its prefixes.
(s/defrelation :in-band
  "Code namespace ?ns belongs to Band ?b — DERIVED from the namespace path: ?ns's name starts
   with one of ?b's declared prefixes. No membership is ever authored."
  [?ns ?b]
  [(is ?b ::Band) (prefix ?b ?px) [?px :val/value ?p]
   (is ?ns :fukan.common.extraction.clojure.module/Ns) (named ?ns ?n)
   [(clojure.string/starts-with? ?n ?p)]])

(defstructure Band
  "A stratum of the codebase: the namespaces under its `:prefix`es, plus the bands it is allowed
   to depend on. The laws are the SLOT SEMANTICS of `:may-depend` — without them the declaration
   is prose.

   All three are naturally vacuous in a project that declares no Bands, which is most of them:
   the conformance and acyclicity laws quantify over declared edges, and the coverage law is
   explicitly gated on at least one Band existing."
  {:prefix     [:+ NsPrefix]  ; the namespace prefixes whose members this band claims
   :may-depend [:* Band]}     ; the bands it may depend on (declared intent)

  (law "every cross-band namespace dependency follows a declared :may-depend edge"
    ;; The offender is the whole EDGE, plus the two bands it crosses. A law naming only the
    ;; caller reports that a namespace is in the wrong without saying which of its requires is
    ;; the wrong one — true, and useless to whoever has to act on it. All four vars are bound in
    ;; the body already, so carrying them costs nothing.
    ;;
    ;; The var NAMES are load-bearing too: they travel with the rows (`law/check`'s `:vars`) and
    ;; are what a consumer labels the columns with, so `?from`/`?to` renders as a finding an
    ;; agent can act on where `?a`/`?b` renders as four names in a line.
    {:scope :global
     :offenders [?from ?to ?from-band ?to-band]
     :rules [[(declared-dep ?s ?t) (is ?s ::Band) (may-depend ?s ?t)]]
     :where [(ns-depends ?from ?to)
             (in-band ?from ?from-band) (in-band ?to ?to-band)
             [(not= ?from-band ?to-band)]
             (not (declared-dep ?from-band ?to-band))]})

  (law "every namespace belongs to a band, once any band is declared"
    ;; Without this the whole design is opt-in. `in-band` is derived from the path, so a
    ;; namespace under a prefix no band claims is not an offender anywhere — it is INVISIBLE: the
    ;; cross-band law needs both ends in a band to fire, so an unbanded package can call anything
    ;; and be called by anything and the model stays green.
    ;;
    ;; GATED on a Band existing, and the gate is not politeness to non-adopters — it is what the
    ;; rule means. A project that declares no bands is asserting nothing about coverage. A project
    ;; that declares one is asserting a partition, and a partition with a hole in it is the
    ;; blindness above wearing a declaration.
    {:scope :global
     :offenders [?ns]
     :rules [[(some-band ?b) (is ?b ::Band)]]
     :where [(some-band ?_b)
             (is ?ns :fukan.common.extraction.clojure.module/Ns)
             (not-join [?ns] (in-band ?ns ?b))]})

  (law "the :may-depend graph is acyclic — no band transitively depends on itself"
    {:offenders [?band]
     :rules [[(band-reaches ?s ?t) (may-depend ?s ?t)]
             [(band-reaches ?s ?t) (may-depend ?s ?mid) (band-reaches ?mid ?t)]]
     :where [(band-reaches ?band ?band)]}))
