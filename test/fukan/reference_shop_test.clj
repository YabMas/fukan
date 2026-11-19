(ns fukan.reference-shop-test
  "THE ONE WAY, END TO END. `reference/shop` is a small system written the way a project is
   meant to be: its domain declared in Lexicon, every signature written in the domain's words,
   its design saying the same in the same words. This holds that fukan, told nothing, reads all
   of it and finds nothing wrong — and that the green is not an empty one.

   The project's own tests (`clojure -M:test` there) hold the other half: the same signatures
   enforced on running code."
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.canvas.core.structure :as s]
            [fukan.common.extraction.core :as tc]
            [fukan.common.extraction.lexicon :as lx]
            [fukan.cozo.build :as build]
            [fukan.cozo.law :as law]
            [fukan.cozo.query :as cq]))

(def ^:private designs '[design.store design.checkout design.billing design.front])

(defonce ^:private loaded
  ;; The design is found on the project's own classpath when the project checks itself; here
  ;; its files are loaded where they lie, in dependency order.
  (delay (doseq [n designs]
           (load-file (str "reference/shop/" (.replace (name n) "." "/") ".clj")))))

(defn- model []
  @loaded
  ;; the shop, and the request-handling library it uses — which the shop's own check finds on
  ;; its classpath, and this one is handed as a second root
  (let [p ["reference/shop/src" "reference/web/src"]]
    (build/model->cozo designs
                       {:planes {"code"   (:roots (tc/extract-roots p))
                                 "domain" (:roots (lx/extract-roots p))}})))

(defn- q [db query & args] (set (apply cq/q query db (s/vocab-rules) args)))

(deftest every-law-holds-and-not-vacuously
  (let [db (model)]
    (is (= [] (law/check db)))
    (testing "each of the thirteen designed operations is realized by a function"
      (is (= 13 (count (q db '[:find ?o ?f :in $ %
                               :where (is ?o :fukan.common.vocab.code.operation/Operation)
                                      (corresponds ?o ?f)])))))
    (testing "and each of those functions wrote a signature the dialect read"
      (is (= 13 (count (q db '[:find ?f :in $ %
                               :where (is ?o :fukan.common.vocab.code.operation/Operation)
                                      (corresponds ?o ?f) (signature ?f ?sig)]))))
      (is (= #{} (q db '[:find ?f :in $ % :where [?f :val/signature-unread true]]))))))

(deftest every-shape-that-flows-has-a-name-in-the-domain
  (let [db (model)]
    (testing "the views, each under its whole name: two summaries, neither the other"
      (is (= #{"checkout/receipt" "checkout/placement" "checkout/summary" "checkout/price"
               "billing/summary" "front/visitor" "web/route" "web/caller"}
             (set (map first (q db '[:find ?n :in $ % :where (is ?v ::lx/View) [?v :entity/name ?n]])))))
      (is (= #{["shop.checkout" "summary" "order/total"] ["shop.checkout" "summary" "order/id"]
               ["shop.billing" "summary" "customer/id"] ["shop.billing" "summary" "customer/name"]
               ["shop.billing" "summary" "customer/address"]}
             (q db '[:find ?nn ?fn ?an :in $ %
                     :where (is ?f :fukan.common.extraction.clojure.operation/Fn)
                            [?f :entity/name ?fn] [(= ?fn "summary")]
                            (gives-attribute ?f ?a) (child ?ns ?f)
                            [?ns :entity/name ?nn] [?a :entity/name ?an]]))))
    (testing "a value shape passes under the name of the attribute embedding it"
      (is (= #{["customer/address" "address"]}
             (q db '[:find ?an ?vn :in $ % :where (embeds ?a ?v) [?a :entity/name ?an] [?v :entity/name ?vn]]))))))

(deftest what-the-code-reads-is-what-its-signatures-say
  (let [db (model)]
    (testing "how far the domain's words travel, by namespace"
      (is (= #{["shop.checkout" "order"] ["shop.checkout" "customer"] ["shop.checkout" "product"]
               ["shop.billing" "order"] ["shop.billing" "customer"]
               ["shop.front" "login"] ["shop.front" "order"] ["shop.front" "customer"]}
             (lx/footprint db))))
    (testing "no function claims to know something nothing explains"
      (is (= #{} (lx/unread-claims db))))))

(deftest a-system-speaks-more-than-one-language
  (let [db (model)]
    (testing "two vocabularies, each owning its shapes; the shop's draws on the shared one"
      (is (= #{"shop" "web"}
             (set (map first (q db '[:find ?n :in $ % :where (is ?v ::lx/Vocabulary) [?v :entity/name ?n]])))))
      (is (= #{["shop" "customer"] ["shop" "login"] ["shop" "order"] ["shop" "line"]
               ["shop" "product"] ["shop" "address"]
               ["web" "web.request"] ["web" "web.session"]}
             (q db '[:find ?vn ?sn :in $ % :where (declares-shape ?v ?s)
                     [?v :entity/name ?vn] [?s :entity/name ?sn]])))
      (is (= #{["shop" "web"]}
             (q db '[:find ?an ?bn :in $ % :where (draws-on ?a ?b)
                     [?a :entity/name ?an] [?b :entity/name ?bn]]))))
    (testing "a reference crosses from one to the other, by the entity's one name"
      (is (contains? (:references (lx/domain-map db)) ["login" "web.session"])))
    (testing "which namespace speaks which language"
      (is (= #{["shop.checkout" "shop"] ["shop.billing" "shop"]
               ["shop.front" "shop"] ["shop.front" "web"]
               ["web.routing" "web"]}
             (lx/languages db))))
    (testing "and the functions standing where the two meet are the front's, and only those"
      (is (= {["shop.front" "customer"] #{"shop" "web"}
              ["shop.front" "receipt"]  #{"shop" "web"}}
             (lx/translators db))))))
