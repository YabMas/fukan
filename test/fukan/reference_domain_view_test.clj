(ns fukan.reference-domain-view-test
  "THE REFERENCE DOMAIN, DRAWN. `reference/shop` speaks two vocabularies — its own and the
   request-handling library's — and this holds that the explorer, asked for a view of the domain
   plane, draws exactly that domain: the two vocabularies, the shapes each declares, their
   attributes, the shop drawing on the web, and the login referring across to the web's session.
   And that it draws nothing else — no namespace, no function, no declared design — while the
   same model asked for no plane still draws the declared design alone."
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.common.extraction.core :as tc]
            [fukan.common.extraction.lexicon :as lx]
            [fukan.canvas.projection.view :as view]
            [cheshire.core]
            [fukan.cozo.build :as build]
            [fukan.cozo.query :as cq]
            [fukan.web.explorer :as explorer]))

(def ^:private designs '[design.store design.checkout design.billing design.front])

(defonce ^:private loaded
  (delay (doseq [n designs]
           (load-file (str "reference/shop/" (.replace (name n) "." "/") ".clj")))))

(defn- model
  "The shop and the library it uses, read by the same two producers the composition root
   registers, each under its own plane — or, `design-only`, the shop's design alone."
  ([] (model false))
  ([design-only]
   @loaded
   (let [p ["reference/shop/src" "reference/web/src"]]
     (build/model->cozo designs
                        (if design-only
                          {}
                          {:planes {"code"   (:roots (tc/extract-roots p))
                                    "domain" (:roots (lx/extract-roots p))}})))))

(def ^:private request
  {"nest" "declares-shape,part,attribute" "link" "draws-on,entity-references"})

(def ^:private shop "vocabulary:shop")
(def ^:private web "vocabulary:web")

(defn- graph [db params]
  (let [[tag g] (:answer (explorer/answer (constantly db) params))]
    (is (= :graph tag) (str "answered " tag " " g))
    g))

(defn- with-open-paths [params paths]
  (assoc params "open" (cheshire.core/generate-string paths)))

(deftest the-domain-plane-is-drawn-as-the-domain
  (let [db     (model)
        g      (graph db (with-open-paths (assoc request "plane" "domain")
                                         [[shop] [web] [shop ":customer"]]))
        level  (fn [depth] (set (map :label (filter #(= depth (count (:path %))) (:nodes g)))))]
    (testing "the root level is exactly the two vocabularies"
      (is (= #{"shop" "web"} (level 1))))
    (testing "each opens onto the shapes it declares"
      (is (= #{"address" "customer" "login" "product" "order" "line" "web.request" "web.session"}
             (level 2)))
      (is (= #{"web.request" "web.session"}
             (set (map :label (filter #(= web (first (:path %))) (filter #(= 2 (count (:path %))) (:nodes g))))))))
    (testing "a shape opens onto its attributes, and onto the entities that are its parts"
      (is (= #{"customer/id" "customer/name" "customer/address" "customer/orders" "customer/logins"
               "login" "order"}
             (set (map :label (filter #(= [shop ":customer"] (pop (:path %))) (:nodes g)))))))
    (testing "the shop draws on the web"
      (is (some #(and (= [shop] (:from %)) (= [web] (:to %)) (= "draws-on" (:relation %))) (:edges g))))
    (testing "the login refers across, to the web's session"
      (is (some #(and (= ":login" (peek (:from %))) (= [web ":web.session"] (:to %))
                      (= "entity-references" (:relation %)))
                (:edges g))))
    (testing "nothing drawn is off the domain plane: no namespace, no function, no design"
      (let [domain (into #{} (map (fn [[e]] (:entity/id (cq/entity db e)))) (view/universe db "domain"))]
        (is (every? #(domain (peek (:path %))) (:nodes g)))
        (is (every? #(and (domain (peek (:from %))) (domain (peek (:to %)))) (:edges g)))))))

(deftest asked-for-no-plane-the-same-model-draws-the-declared-design
  (let [db (model)]
    (testing "the producers' planes change nothing about a view that names none"
      (let [design {"nest" "child" "link" "delegates" "open" (cheshire.core/generate-string [["design.checkout/checkout"]])}]
        (is (= (graph (model true) design) (graph db design)))))
    (testing "and the domain's relations, asked of the declared design, place nothing in it"
      (is (= [] (:nodes (graph db request)))))))
