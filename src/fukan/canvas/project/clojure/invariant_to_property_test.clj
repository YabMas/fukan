(ns fukan.canvas.project.clojure.invariant-to-property-test
  "Clojure-lens projection — invariant Affordance → `clojure.test.check`
   `defspec` property-test skeleton under `test/`.

   Canvas `(invariant \"MajorityRequiredForLeadership\" \"…descriptive doc…\"
   (holds-that \"no node is leader for term T without > N/2 grants for T\"))`
   declares a *timeless behavioral commitment*. Its natural code-side
   counterpart is a generative property test that runs the commitment
   against generated model states — not a `defn` predicate stub. This
   projection emits the canonical `defspec` skeleton; the implementing-LLM
   fills in the generator + property body.

   Phase 8 Sprint 1 design picked the **migrate** path: this projection
   becomes the default for invariants. The existing
   `invariant-to-predicate` projection stays registered on
   `[:clojure :canvas/invariant]` for the opt-in predicate case (canvas
   declarations carrying `(projects-to :predicate)`). The default routes
   here via the synthetic dispatch key `:canvas/invariant+property-test`
   (Option β — augmented `dispatch-key-of` in
   `fukan.canvas.project.core`).

   Address convention:
     ns       fukan.<module-coord>-test
     symbol   <kebab(entity-name)>-property
     file     test/fukan/<module-coord-path>_test.clj

   Template idiom: `clojure.test.check` `defspec` with a placeholder
   generator (`gen/return ::placeholder`) and an audit-trail `throw`
   body that carries the canvas-side commitment as `ex-info` payload.
   The placeholder is deliberately broken — implementing-LLMs that copy
   mechanically hit the throw rather than emit a silently-passing test.

   Holds-that prose is carried in BOTH the comment AND the `ex-info`
   payload so the implementing-LLM sees the commitment at both source
   altitudes.

   Routes: `[:clojure :canvas/invariant+property-test]`.

   See doc/plans/2026-05-28-invariant-projection-design.md §§ 3, 5, 6, 7."
  (:require [clojure.string :as str]
            [fukan.canvas.project.core :as core]
            [fukan.target.clojure.address :as addr]))

(def ^:private iteration-count
  "Default `clojure.test.check` iteration count for the generated
   `defspec`. 100 mirrors `clojure.test.check`'s own default; the
   implementing-LLM may raise it for invariants whose generators
   produce expensive state walks."
  100)

(defn- escape-prose
  [s]
  (when s
    (-> s
        str/trim
        (str/replace #"\"" "\\\\\""))))

(defn- ns->test-file-path
  "Convention: prefix `test/`, dot→slash on the namespace, hyphen→underscore
   on the filename. The address-registry's ns format already carries the
   `-test` suffix for property-test projections, so this fn only does the
   path-shape swap."
  [ns-str]
  (when (string? ns-str)
    (str "test/"
         (-> ns-str
             (str/replace #"-" "_")
             (str/replace #"\." "/"))
         ".clj")))

(defn- prose-envelope
  "The full prose carried alongside the skeleton — descriptive doc
   first, then the holds-that clause, then a property-check framing
   that names the test-side artifact convention for the implementing-LLM."
  [invariant-name doc holds-that]
  (str "Invariant: " invariant-name "."
       (when (seq doc) (str " " (str/trim doc)))
       (when (seq holds-that)
         (str "\n\nWhat must hold: " holds-that "."))
       "\n\nProperty-check approach: this is a `clojure.test.check` property"
       " test, not a predicate. The implementing-LLM should replace the"
       " placeholder generator (`gen/return ::placeholder`) with a generator"
       " that produces representative model states, then replace the"
       " audit-trail `throw` body with `prop/for-all`'s expected boolean"
       " body — assert the invariant holds against each generated state."))

(defn- render-template
  [{:keys [symbol]} stable-id doc invariant-name holds-that]
  (let [doc-comment (when (seq doc)
                      (str "  ;; " (str/replace (str/trim doc) #"\n" "\n  ;; ") "\n"
                           "  ;;\n"))
        holds-that-comment (when (seq holds-that)
                             (str "  ;; What must hold: " holds-that ".\n"
                                  "  ;;\n"))]
    (str "(defspec " symbol " " iteration-count "\n"
         "  (prop/for-all [model (gen/return ::placeholder)]\n"
         "    ;; Invariant: " invariant-name ".\n"
         doc-comment
         holds-that-comment
         "    ;; Property-check approach: generate a representative model state,\n"
         "    ;; walk the relevant entities, and assert the invariant. Replace\n"
         "    ;; the placeholder generator and the throw-body below.\n"
         "    (throw (ex-info \"" symbol ": not yet implemented\"\n"
         "                    {:canvas-id        \"" stable-id "\"\n"
         "                     :invariant-name   \"" invariant-name "\""
         (when (seq holds-that)
           (str "\n                     :holds-that       \"" (escape-prose holds-that) "\""))
         "\n                     :iteration-count  " iteration-count "}))))")))

(defmethod core/project [:clojure :canvas/invariant+property-test]
  [_lens-id element opts]
  (let [{:keys [stable-id entity-name module-coord doc holds-that]} element
        registry (:registry opts)
        address  (addr/canonical registry
                                 :primitive/rule
                                 :projection-kind/property-test
                                 module-coord
                                 entity-name)
        target   {:path      (ns->test-file-path (:ns address))
                  :namespace (:ns address)
                  :symbol    (:name address)}]
    {:projection-kind    :clojure/invariant-to-property-test
     :lens-id            :clojure
     :model-element-kind :Affordance
     :model-element-id   stable-id
     :target             target
     :template           (render-template target stable-id doc entity-name holds-that)
     :prose              (prose-envelope entity-name doc holds-that)
     :context            (cond-> {:canvas-source-ref (str "canvas/"
                                                          (str/replace module-coord #"\." "/")
                                                          ".clj")
                                  :doc-source        :canvas/invariant-doc+holds-that
                                  :invariant-name    entity-name
                                  :projection-kind   :projection-kind/property-test
                                  :test-side?        true}
                           (seq holds-that) (assoc :holds-that holds-that))}))
