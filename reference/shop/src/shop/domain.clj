(ns shop.domain
  "The shop's vocabulary, and the one place its words are made resolvable.

   Every shape that flows through the system is named here or by a view over what is named
   here. A signature is written in those names and nothing else: an attribute where one value
   passes (`:order/id`), a view where a shape does (`:checkout/receipt`)."
  (:require [lexicon.core :as lexicon]
            [malli.core :as m]
            [malli.registry :as mr]
            [shop.domain.order :as order]))

(def vocabulary
  (lexicon/vocabulary [order/Address order/Customer order/Product order/Order order/Line]))

(defonce ^:private words
  (atom (merge (m/default-schemas) (lexicon/registry vocabulary))))

;; Malli builds a function's schema when the function is defined, so the words have to
;; resolve by then. This is the only global the shop installs.
(mr/set-default-registry! (mr/mutable-registry words))

(defn install!
  "Make the named `views` (as `lexicon/views` gives them) words a signature may use. Called by
   the namespace declaring them, before its first function."
  [views]
  (swap! words merge views)
  views)

(defn view
  "The view named `k`."
  [k]
  (let [v (get @words k)]
    (when-not (lexicon/view? v)
      (throw (ex-info (str "no view " k) {:view k})))
    v))
