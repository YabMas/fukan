(ns demos.self.self-test
  "Regression for the candidate re-grammar of fukan's subject. The point of this demo is not
   a domain — it is to test whether better abstractions express MORE than they cost. So the
   asserts are about the design CLAIMS becoming checkable:

     - the well-formed self-model satisfies every law (the shape is consistent);
     - the IN-dual is real: a source with a bogus polarity is caught;
     - the OUT-dual is real: an act with a bogus mode is caught;
     - #4 is emergent and enforced: a code-up source with no correspondence trips the
       loop-closes law, and a correspondence that lowers a non-synthesise act is caught.

   Each negative case is a design idea the flat faculty-graph could state only in prose, now
   a law. Run via `clj -M:demos`."
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.canvas.core.structure :as s]
            [fukan.canvas.core.assemble :as a]
            [demos.self.model.fukan :as fukan]
            [demos.self.vocab.core :refer [Model Lens Output Source Act Correspondence Primitive]]))

(defn- laws [db] (set (map :law (s/check db))))

(deftest self-model-is-well-formed
  (testing "fukan's subject, in the candidate grammar, satisfies every law"
    (is (empty? (s/check (fukan/build))))))

;; ── a minimal well-formed fixture the negative cases perturb ──────────────────

(Primitive ^{:name "p"} prim)
(Model ^{:name "M"} mdl {:made-of prim})
(Lens ^{:name "L"} lns {:focus "a slice"})
(Output ^{:name "O"} out)

;; case: the origin distinction — a source with a bogus polarity
(Source ^{:name "bad"} bad-source {:into mdl :polarity "sideways"})

(deftest in-dual-is-enforced
  (testing "a source whose polarity is neither design-down nor code-up is caught"
    (let [db (a/assemble-vars [#'prim #'mdl #'bad-source])]
      (is (contains? (laws db) "Source.polarity value must satisfy [:enum \"design-down\" \"code-up\"]")))))

;; case: OUT-dual — an act with a bogus mode
(Act ^{:name "bad"} bad-act {:reads mdl :through lns :mode "ponder" :yields out})

(deftest out-dual-is-enforced
  (testing "an act whose mode is neither analyse nor synthesise is caught"
    (let [db (a/assemble-vars [#'prim #'mdl #'lns #'out #'bad-act])]
      (is (contains? (laws db) "Act.mode value must satisfy [:enum \"analyse\" \"synthesise\"]")))))

;; case: #4 enforced from the in-side — a code-up source with no correspondence
(Source ^{:name "extract"} orphan-extract {:into mdl :polarity "code-up"})

(deftest correspondence-emerges-or-the-loop-is-open
  (testing "a code-up source with no correspondence trips the loop-closes law (#4 enforced from the in-side)"
    (let [db (a/assemble-vars [#'prim #'mdl #'orphan-extract])]
      (is (contains? (laws db) "the loop closes — every code-up source is matched by a correspondence")))))

;; case: the adjunction is well-typed — a correspondence lowering a non-synthesise act
(Source ^{:name "extract"} mis-up {:into mdl :polarity "code-up"})
(Act ^{:name "probe"} mis-analyse {:reads mdl :through lns :mode "analyse" :yields out})
(Correspondence ^{:name "c"} mis-corr {:lifts mis-up :lowers mis-analyse})

(deftest correspondence-must-pair-the-right-poles
  (testing "a correspondence that lowers a non-synthesise act is caught (the adjunction is well-typed)"
    (let [db (a/assemble-vars [#'prim #'mdl #'lns #'out #'mis-up #'mis-analyse #'mis-corr])]
      (is (contains? (laws db) "a correspondence lowers a synthesise act")))))
