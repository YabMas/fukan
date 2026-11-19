(ns shop.shop-test
  "The shop running, with every signature enforced: the words a signature is written in are
   real schemas, so a value that is not what the word says is refused where it passes."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [malli.core :as m]
            [malli.instrument :as mi]
            [shop.billing :as billing]
            [shop.checkout :as checkout]
            [shop.store :as store]))

(use-fixtures :once (fn [f] (mi/instrument!) (f) (mi/unstrument!)))

(use-fixtures :each
  (fn [f]
    (store/clear!)
    (store/put! :customer {:customer/id 1 :customer/name "Ada"
                           :customer/address {:address/street "1 Lane" :address/city "Leiden"}})
    (store/put! :product {:product/id 10 :product/title "Tea" :product/price 4})
    (store/put! :product {:product/id 11 :product/title "Pot" :product/price 20})
    (f)))

(deftest an-order-is-placed-and-read-back
  (is (= {:order/id 100 :order/customer 1 :order/total 24} (checkout/place 100 1 [10 11])))
  (is (= {:order/id 100 :order/total 24 :customer/name "Ada"} (checkout/receipt 100)))
  (is (= "Ada order 100 total 24" (checkout/render (checkout/receipt 100))))
  (is (nil? (checkout/receipt 999))))

(deftest a-signature-is-enforced-in-the-words-it-is-written-in
  (testing "an attribute where one value passes"
    (is (thrown? Exception (checkout/receipt "100"))))
  (testing "a view where a shape does"
    (is (thrown? Exception (checkout/render {:order/id 100}))))
  (testing "what comes back is held to the view named"
    (store/put! :order {:order/id 7 :order/customer 1 :order/total "a lot"})
    (is (thrown? Exception (checkout/receipt 7)))))

(deftest a-views-name-is-whole
  (checkout/place 100 1 [10])
  (is (= {:order/id 100 :order/total 4} (checkout/summary 100)))
  (is (= {:customer/id 1 :customer/name "Ada"
          :customer/address {:address/street "1 Lane" :address/city "Leiden"}}
         (billing/summary 1)))
  (is (not (m/validate :billing/summary (checkout/summary 100)))))

(deftest a-value-shape-passes-under-its-attributes-name
  (is (= {:address/street "1 Lane" :address/city "Leiden"} (billing/destination 1)))
  (is (m/validate :customer/address (billing/destination 1)))
  (checkout/place 100 1 [10 11])
  (is (= 24 (billing/owed 100))))
