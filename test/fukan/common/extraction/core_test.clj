(ns fukan.common.extraction.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.cozo.build :as build]
            [fukan.cozo.query :as cq]
            [fukan.canvas.core.structure :as s]      ; vocab-rules — for the drift READING below
            [fukan.model.extraction :as extraction]
            [fukan.model.pipeline :as pipeline]
            [fukan.common.extraction.core :as tc]
            [fukan.common.extraction.clojure.effect :as clj-effect]
            [fukan.common.extraction.clojure.module :as clj-module]))

;; Register fukan's FACT extractor so build-model's unified build runs it (the proof).
(extraction/register-fact-extractor! (fn [root] (tc/extract-roots [root])))

(defn- extract
  "The extraction FACTS for `paths` assembled into a code-only Cozo substrate (no canvas nss)
   with the :calls graph grounded + grammar reflected. Several paths because the cross-module
   fixture is two files, and each only resolves against the other when they are analysed together."
  [& paths]
  (build/model->cozo [] (tc/extract-roots (vec paths))))

(deftest clojure-effect-classification-is-owned-by-the-clojure-extractor
  (testing "Clojure var-usages classify direct effects outside the generic Effect vocab"
    (is (= {["demo" "load"] #{:io}
            ["demo" "boom"] #{:throws}}
           (clj-effect/op-effects [{:from 'demo :from-var 'load :to 'clojure.core :name 'slurp}
                                   {:from 'demo :from-var 'boom :to 'clojure.core :name 'throw}
                                   {:from 'demo :from-var 'log :to 'clojure.core :name 'println}])))))

(deftest extracts-functions-as-operations
  (testing "the clj-kondo extractor emits an Fn (the Operation codomain) per defn/defn-, with privacy"
    (let [db  (extract "test/fixtures/target/sample.clj")
          ops (into {} (cq/q '[:find ?n ?p
                              :where [?e :structure/of :fukan.common.extraction.clojure.operation/Fn]
                                     [?e :entity/name ?n] [?e :val/private ?p]]
                            db))]
      (is (= {"alpha" false "beta" false "delta" true} ops)
          "every defn/defn- becomes an Fn; the def (gamma) is ignored; defn- is private"))))

(deftest stores-malli-schema-as-one-decomposed-signature
  (testing "an annotated defn's :malli/schema is stored as ONE `Schema` — the type dialect's own
            node — not as a blob and not decomposed into `:in`/`:out` slots of `Fn`. Until
            2026-08-29 it was the latter, which meant fukan restated at the vocabulary altitude
            what the dialect already modelled; the decomposition still exists, it just lives
            entirely inside the Schema subgraph, one level down."
    (let [db  (extract "test/fixtures/target/sample.clj")
          sig (ffirst (cq/q '[:find ?s
                              :where [?e :structure/of :fukan.common.extraction.clojure.operation/Fn]
                                     [?e :entity/name "alpha"]
                                     [?r :rel/from ?e] [?r :rel/kind :signature] [?r :rel/to ?s]]
                            db))
          out (ffirst (cq/q '[:find ?k :in $ ?s
                              :where [?r :rel/from ?s] [?r :rel/kind :out] [?r :rel/to ?o] [?o :val/kind ?k]]
                            db sig))
          ins (cq/q '[:find [?k ...] :in $ ?s
                      :where [?r :rel/from ?s] [?r :rel/kind :in] [?r :rel/to ?i] [?i :val/kind ?k]]
                    db sig)]
      (is (= "=>" (:val/kind (cq/entity db sig))) "alpha's signature is one arrow Schema")
      (is (= "int" out) "whose :out is a Schema of kind int")
      (is (= ["int"] ins) "and whose params are [int] — queryable, just one hop deeper"))))

(deftest operations-are-owned-by-their-subsystem
  (testing "each namespace becomes an Ns (the Module codomain) that owns its Fns (via :child relations)"
    (let [db    (extract "test/fixtures/target/sample.clj")
          owned (cq/q '[:find ?mn ?on
                       :where [?m :structure/of :fukan.common.extraction.clojure.module/Ns] [?m :entity/name ?mn]
                              [?r :rel/kind :child] [?r :rel/from ?m] [?r :rel/to ?o]
                              [?o :structure/of :fukan.common.extraction.clojure.operation/Fn] [?o :entity/name ?on]]
                     db)]
      (is (= #{["sample" "alpha"] ["sample" "beta"] ["sample" "delta"]} (set owned))
          "the `sample` namespace is an Ns owning all three functions"))))

(deftest emits-calls-between-operations
  (testing "the extractor populates :calls from clj-kondo var-usages — beta calls alpha"
    (let [db    (extract "test/fixtures/target/sample.clj")
          calls (cq/q '[:find ?fromn ?ton
                       :where [?cr :rel/kind :calls] [?cr :rel/from ?f] [?cr :rel/to ?t]
                              [?f :entity/name ?fromn] [?t :entity/name ?ton]]
                     db)]
      (is (contains? (set calls) ["beta" "alpha"])
          "beta -> alpha is emitted as a :calls relation")
      (is (not (some (fn [[a b]] (= a b)) calls)) "no self-call edges"))))

(deftest extracted-modules-carry-provenance
  (testing "each extracted Ns is stamped :val/extracted true"
    (let [db (extract "test/fixtures/target/sample.clj")]
      (is (true? (ffirst (cq/q '[:find ?x :where [?m :structure/of :fukan.common.extraction.clojure.module/Ns]
                                              [?m :entity/name "sample"] [?m :val/extracted ?x]] db)))
          "the sample Ns is provenance-stamped"))))

;; ── the SUPPLY side: a defmulti's methods are nodes, not part of the defmulti ─
;; `poly` owns the surface and two co-owned methods; `poly-ext` writes a third. The pair is the
;; only way to see the boundary property that matters — whose dependency a method body's calls
;; are — so both tests below analyse the two files together.

(def ^:private poly-fixture
  ["test/fixtures/target/poly.clj" "test/fixtures/target/poly_ext.clj"])

(defn- calls-in
  "The extracted `:calls` graph as a set of [caller-name callee-name] pairs."
  [db]
  (set (cq/q '[:find ?fromn ?ton
               :where [?c :rel/kind :calls] [?c :rel/from ?f] [?c :rel/to ?t]
                      [?f :entity/name ?fromn] [?t :entity/name ?ton]] db)))

(deftest a-defmulti-is-an-ordinary-operation-and-delegates-to-nothing
  (testing "demand does not move: a call to a dispatch point is an ordinary call edge, and no
            consumer of :calls learns that it IS one. What moves is supply — a surface stops
            calling what its suppliers call. That looks like a regression and is the point: a
            dispatch point delegates to nothing; its methods do."
    (let [db    (apply extract poly-fixture)
          calls (calls-in db)]
      (is (true? (ffirst (cq/q '[:find ?x :where [?o :structure/of :fukan.common.extraction.clojure.operation/Fn]
                                              [?o :entity/name "render-shape"] [?o :val/extracted ?x]] db)))
          "render-shape (a defmulti) is still an extracted Fn")
      (is (contains? calls ["describe" "render-shape"])
          "describe -> render-shape resolves as a :calls edge — a defmulti is an ordinary op")
      (is (not (contains? calls ["render-shape" "area"]))
          "the co-owned :circle method's body call is the METHOD's, not the multimethod's")
      (is (contains? calls ["poly/render-shape[:circle]@poly" "area"])
          "…and it lands on the method that wrote it"))))

(deftest a-method-is-a-node-of-the-namespace-that-wrote-it
  (testing "identity is the TRIPLE (surface, dispatch value, implementing namespace), which is
            what makes two namespaces supplying the same surface two nodes rather than one"
    (let [db    (apply extract poly-fixture)
          owned (set (cq/q '[:find ?nsn ?mn
                             :where [?ns :structure/of :fukan.common.extraction.clojure.module/Ns]
                                    [?r :rel/kind :child] [?r :rel/from ?ns] [?r :rel/to ?m]
                                    [?m :structure/of :fukan.common.extraction.clojure.method/Method]
                                    [?ns :entity/name ?nsn] [?m :entity/name ?mn]] db))]
      (is (= #{["poly"     "poly/render-shape[:circle]@poly"]
               ["poly"     "poly/render-shape[:square]@poly"]
               ["poly-ext" "poly/render-shape[:rect]@poly-ext"]
               ["poly-ext" "poly/render-shape[:inline]@poly-ext"]
               ["poly-ext" "poly/render-shape[:nested]@poly-ext"]
               ["poly-ext" "poly/render-shape[:tagged]@poly-ext"]}
             owned)
          "a method belongs to the namespace that WRITES it, never the one owning the surface"))))

(deftest a-fulfils-edge-carries-its-dispatch-value
  (testing "the discriminator rides the EDGE, as `:rel/label` — it is a property of the supply
            relation, not of the supplier: it is what selects this method for that surface"
    (let [db (apply extract poly-fixture)]
      (is (= #{["poly/render-shape[:circle]@poly" "render-shape" ":circle"]
               ["poly/render-shape[:square]@poly" "render-shape" ":square"]
               ["poly/render-shape[:rect]@poly-ext" "render-shape" ":rect"]
               ["poly/render-shape[:inline]@poly-ext" "render-shape" ":inline"]
               ["poly/render-shape[:nested]@poly-ext" "render-shape" ":nested"]
               ["poly/render-shape[:tagged]@poly-ext" "render-shape" ":tagged"]}
             (set (cq/q '[:find ?mn ?sn ?d
                          :where [?r :rel/kind :fulfils] [?r :rel/from ?m] [?r :rel/to ?s]
                                 [?r :rel/label ?d]
                                 [?m :entity/name ?mn] [?s :entity/name ?sn]] db)))))))

(deftest a-cross-module-method-contributes-exactly-its-own-edges
  (testing "the arrow this whole change exists to turn around. Before, a method's body calls were
            DONATED to the multimethod, so `poly` appeared to depend on `poly-ext` — the reverse
            of the truth, and the one direction a reader of the dependency graph could not
            question, because nothing said an edge was missing."
    (let [db    (apply extract poly-fixture)
          calls (calls-in db)]
      (is (contains? calls ["poly/render-shape[:rect]@poly-ext" "width"])
          "the method's body call attributes to the method, in its own namespace — and it survives
           the row-less `if`/`let`/`->` artifacts `some->` injects around it")
      (is (not (contains? calls ["render-shape" "width"]))
          "the surface's owner gains no dependency on the satisfier's callees")
      (is (not (contains? (clj-module/ns-dependencies db) ["poly" "poly-ext"]))
          "so the inverted namespace dependency is gone"))))

(deftest supplying-a-surface-is-a-namespace-dependency
  (testing "the direction that surprises people, and the one no call edge can carry: a method is
            invoked through the surface, never by name, so without a supply clause in `ns-depends`
            this dependency exists in the code and nowhere in the graph.

            The fixture makes the claim exact, and sharply: `poly-ext` calls nothing in `poly`.
            Its method's body reaches only its own functions, and the trailing top-level form
            NAMES `poly/area` but is bounded out of the method, so it contributes nothing. The
            ONE edge below can therefore have come from nothing but the fulfilment."
    (let [db (apply extract poly-fixture)]
      (is (= #{["poly-ext" "poly"]} (clj-module/ns-dependencies db))))))

(deftest a-top-level-form-after-a-method-is-not-attributed-to-it
  (testing "where positional attribution STOPS. A defmethod body call has no enclosing var and the
            analysis offers no extent for the method, so the bound has to come from somewhere: the
            reader, which knows where the next top-level form begins. Without it the forms below
            are captured as the method's body, and since `call-graph` constrains the callee to no
            namespace, that mis-capture puts an edge into `ns-depends` no var of either namespace
            justifies — in front of a `Band` law that reads that graph at :scope :global.

            Both spellings are here because the bound cannot be a column test. Leading whitespace
            before a top-level form is legal, so the indented one is the case a column test waves
            through while looking correct on the other."
    (let [db    (apply extract poly-fixture)
          calls (calls-in db)]
      (is (not (contains? calls ["poly/render-shape[:rect]@poly-ext" "register!"]))
          "`(register! poly/area)` sits outside the method and is no part of it")
      (is (not (contains? calls ["poly/render-shape[:rect]@poly-ext" "area"]))
          "nor is the CROSS-NAMESPACE callee it names — the case that reaches a law")
      (is (not (contains? calls ["poly/render-shape[:rect]@poly-ext" "describe"]))
          "and neither is the INDENTED form's cross-namespace callee, which opens at column 3")
      (is (contains? calls ["poly/render-shape[:rect]@poly-ext" "width"])
          "while the method's real body call, nested inside its form, is still attributed"))))

(deftest ownership-is-bounded-by-form-extent-not-by-row
  (testing "the two spellings a row-granular bound cannot separate, both of which invent a
            CROSS-NAMESPACE edge when they are got wrong — the direction the design says
            attribution must never fail in.

            Neither is exotic. `(defmethod …) (register! …)` on one line is two top-level forms
            sharing a start row; `(do (defmethod …) (f))` is a method whose sibling has the same
            enclosing top-level form it does. A start row cannot tell either pair apart, and a
            column cannot either, because a top-level form may be indented. A form's EXTENT can:
            it either contains a position or it does not."
    (let [db    (apply extract poly-fixture)
          calls (calls-in db)]
      (is (not (contains? calls ["poly/render-shape[:inline]@poly-ext" "area"]))
          "the same-line sibling's cross-namespace callee is not the method's")
      (is (not (contains? calls ["poly/render-shape[:nested]@poly-ext" "describe"]))
          "nor is the nested method's sibling inside the same `do`")
      (is (contains? calls ["poly/render-shape[:inline]@poly-ext" "width"])
          "while the same-line method keeps its own body call")
      (is (contains? calls ["poly/render-shape[:nested]@poly-ext" "width"])
          "and a NESTED method keeps its body rather than being dropped for being nested"))))

(deftest a-permissive-read-must-not-be-a-lossy-one
  (testing "the extent read answers for every alias and every tag, because the adopter namespaces
            this parses are not loaded in this JVM and a throw costs the WHOLE file's method
            bodies. That permissiveness must not become lossy: collapsing a tagged literal to its
            bare value makes it EQUAL to an untagged one beside it, and a set holding both is then
            a `Duplicate key` — the file drops, and a method stands with its body call missing.

            It is the third spelling of one class, after a reader conditional losing an arm and
            two aliases colliding, which is why the fixture carries it rather than a comment."
    (let [db (apply extract poly-fixture)]
      (is (contains? (calls-in db) ["poly/render-shape[:tagged]@poly-ext" "width"])
          "the method in a file holding `#{#inst \"2020-01-01\" \"2020-01-01\"}` keeps its body call"))))

(deftest every-modelled-stage-is-realized-in-src
  (testing "fukan-on-itself: build-model unifies the authored self-model (canvas/)
            with the code extracted from src/ on one graph, and every modelled
            op-layer Operation is backed by a real function — the cross-layer
            correspondence is assertable only because both layers share that graph"
    (let [model      (pipeline/build-model "src")        ; design + extracted code, unified
          ;; the drift READING (ex-:corresponds/Operation.total, dissolved at the essential-correspond
          ;; cutover): modelled Operations with no `corresponds` twin, gated on extraction having run.
          unrealized (set (cq/q '[:find [?n ...] :in $ %
                                  :where (is ?op :fukan.common.vocab.code.operation/Operation) (design ?op)
                                         (is ?_g :fukan.common.extraction.clojure.operation/Fn)
                                         (not-join [?op] (corresponds ?op ?_t))
                                         [?op :entity/name ?n]]
                                model (s/vocab-rules)))]
      ;; sanity: build-model actually brought both layers together
      (is (seq (cq/q '[:find ?s :where [?s :structure/of :fukan.common.vocab.code.operation/Operation]] model)) "model has design Operations")
      (is (seq (cq/q '[:find ?o :where [?o :structure/of :fukan.common.extraction.clojure.operation/Fn]] model)) "build-model extracted code into Fns")
      (is (empty? unrealized)
          (str "every modelled Operation should map to a same-named extracted function; "
               "unrealized (drift): " unrealized)))))
