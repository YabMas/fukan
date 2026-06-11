(ns fukan.canvas.projection.instance-test
  "The instance print-dual: author instances, assemble, render them back, and
   compare against what was authored — the round-trip is the test (the sibling
   of the grammar print-dual's round-trip, one stratum down)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [fukan.canvas.core.assemble :as a]
            [fukan.canvas.core.structure :as s :refer [defstructure]]
            [datascript.core :as d]
            [fukan.canvas.projection.instance :as inst]
            ;; Schema: the INode :shape slot target (reader-expanded malli literals)
            [lib.type.malli :refer [Schema]]))

;; ── fixture vocab: every instance-surface feature ─────────────────────────────

(defstructure ITarget "A named reference target.")

(defstructure ^:value IVal
  "An anonymous value."
  {:n :Int})

(defstructure INode
  "Fixture: scalars, payload, one/optional/plural refs, labels, values, a Schema."
  {:title  :String
   :flag   [:? :Bool]
   :query  [:? {:payload :q} :String]
   :main   ITarget
   :links  [:* ITarget]
   :vals   [:* IVal]
   :shape  [:? Schema]}
  (law "no node may be titled \"bad\""
    :offenders '[?n]
    :where '[[?n :val/title "bad"]]))

(ITarget t-a)
(ITarget ^{:name "B"} t-b)

(INode full
  "A node exercising every encoding."
  {:title "x"
   :query ["the recap" '[[?n :structure/of _]]]
   :main  t-a
   :links [[edge t-a] t-b]
   :vals  [(IVal {:n 1}) (IVal {:n 2})]
   :shape [:vector :keyword]})

(INode bare {:title "y" :main t-a})

(INode ^{:name "bad"} offender {:title "bad" :main t-a})

(defn- db []
  (a/assemble-vars [#'t-a #'t-b #'full #'bare #'offender]))

(defn- by-name [db n]
  (ffirst (d/q '[:find ?e :in $ ?n :where [?e :entity/name ?n]] db n)))

;; ── the round-trip ────────────────────────────────────────────────────────────

(deftest instance-form-round-trips-the-authoring
  (let [d* (db)
        f  (inst/instance-form d* (by-name d* "full"))]
    (is (= '(INode full
              "A node exercising every encoding."
              {:title "x"
               :query ["the recap" [[?n :structure/of _]]]
               :main  t-a
               :links [[edge t-a] t-b]
               :vals  [(IVal {:n 1}) (IVal {:n 2})]
               :shape [:vector :keyword]})
           f)
        "the rendered form IS the authored form (payload as parsed data, like the grammar dual)")))

(deftest name-override-is-recovered-as-meta
  (let [d* (db)
        [_ nm] (inst/instance-form d* (by-name d* "B"))]
    (is (= 't-b nm) "the name position is the binding var")
    (is (= "B" (:name (meta nm))) "the differing entity name rides ^{:name …} metadata")))

(deftest absent-optionals-are-omitted
  (let [d* (db)
        [_ _ m] (inst/instance-form d* (by-name d* "bare"))]
    (is (= {:title "y" :main 't-a} m) "absent optional slots leave no map entry")))

(deftest slotless-instance-renders-headline-only
  (let [d* (db)
        f  (inst/instance-form d* (by-name d* "t-a"))]
    (is (= '(ITarget t-a) f))))

(deftest anonymous-value-renders-as-expression-form
  (let [d* (db)
        [_ _ _doc m] (inst/instance-form d* (by-name d* "full"))]
    (is (= '[(IVal {:n 1}) (IVal {:n 2})] (:vals m))
        "value targets recurse into anonymous expression forms")))

;; ── text renders ──────────────────────────────────────────────────────────────

(deftest instance-text-formats-the-authored-shape
  (let [d* (db)
        txt (inst/instance-text d* (by-name d* "bare"))]
    (is (= (str "(INode bare\n"
                "  {:title \"y\"\n"
                "   :main  t-a})")
           txt))))

(deftest focus-text-renders-a-clause-selected-slice
  (let [d* (db)
        txt (inst/focus-text d* '[(ITarget ?n)])]
    (is (str/includes? txt "(ITarget t-a)"))
    (is (str/includes? txt "(ITarget ^{:name \"B\"} t-b)"))))

(deftest violations-quote-the-offending-form
  (let [d*  (db)
        out (inst/violations-text d* (filter #(= "no node may be titled \"bad\"" (:law %))
                                             (s/check d*)))]
    (is (str/includes? out "✗ no node may be titled \"bad\""))
    (is (str/includes? out "(INode ^{:name \"bad\"} offender")
        "the offender appears as its authored form, fix-adjacent")
    (is (= "No violations — every law holds." (inst/violations-text d* [])))))
