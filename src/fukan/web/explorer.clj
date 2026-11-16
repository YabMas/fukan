(ns fukan.web.explorer
  "The EXPLORER — a held model's declared design, served to a browser.

   Everything that makes a reading reachable over HTTP is in here and nowhere else: the routes,
   the JSON on the wire, the page, and the one place a request's untrusted parameters become a
   view spec and a set of open paths (`parse-request`). The projections it calls are only ever
   handed values of the right shape.

   It is started with a FUNCTION that yields the current model and calls it once per request. It
   keeps no model and nothing about any viewer between requests: what a viewer opened and
   selected travels in the page's address. So a rebuilt model is simply what the next request
   reads, and a db handle closed by a reload is never one this namespace is still holding."
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [fukan.canvas.projection.design :as design]
            [fukan.canvas.projection.instance :as inst]
            [fukan.canvas.projection.view :as view]
            [fukan.cozo.query :as cq]
            [org.httpkit.server :as http])
  (:import [java.net URLDecoder]
           [java.time Instant]))

;; ── the request, parsed ───────────────────────────────────────────────────────

(defn- names
  "A comma-separated parameter as its non-blank names, in order."
  [param]
  (->> (str/split (or param "") #",") (map str/trim) (remove str/blank?) vec))

(defn- read-open
  "The `open` parameter — a JSON array of arrays of strings — as a set of paths, or nil when it
   is anything else."
  [param]
  (if (str/blank? param)
    #{}
    (let [v (try (json/parse-string param) (catch Exception _ nil))]
      (when (and (sequential? v)
                 (every? #(and (sequential? %) (seq %) (every? string? %)) v))
        (into #{} (map vec) v)))))

(defn- read-select
  "The `select` parameter — EDN, a vector of clauses — or `::malformed`. Absent is nil: no
   narrowing."
  [param]
  (if (str/blank? param)
    nil
    (let [v (try (edn/read-string param) (catch Exception _ ::malformed))]
      (if (and (vector? v) (seq v)) v ::malformed))))

(defn ^{:malli/schema [:=> [:cat :StructureDb [:map-of :string :string]] :GraphRequest]}
  parse-request
  "A graph request's untrusted `params` as `[:view spec open]`, or `[:rejected reason]`.

   `nest` and `link` are comma-separated relation names and must each be a relation `db`
   offers to view by — checked here so a mistyped name is answered as the caller's mistake rather
   than surfacing as an engine error about an unknown rule. `open` is a JSON array of paths.
   `select` is EDN clauses binding ?n; whether the engine can evaluate them is not decided here.
   `unplaced` asks for the nodes the nesting says nothing about only when it is exactly `1`.
   `root` is an entity id taken as given: one the model lacks is the projection's to answer."
  [db params]
  (let [known  (into #{} (map :name) (:relations (view/views db)))
        nest   (names (get params "nest"))
        link   (names (get params "link"))
        open   (read-open (get params "open"))
        select (read-select (get params "select"))
        stray  (remove known (concat nest link))]
    (cond
      (empty? nest)           [:rejected "nest: name at least one relation to nest by"]
      (seq stray)             [:rejected (str "not a relation this model offers to view by: "
                                              (str/join ", " (distinct stray)))]
      (nil? open)             [:rejected "open: expected a JSON array of paths, each an array of ids"]
      (= ::malformed select)  [:rejected "select: expected an EDN vector of clauses binding ?n"]
      :else                   [:view
                               (cond-> {:nest nest :link link}
                                 select (assoc :select select)
                                 (= "1" (get params "unplaced")) (assoc :unplaced true)
                                 (not (str/blank? (get params "root")))
                                 (assoc :root (get params "root")))
                               open])))

(defn ^{:malli/schema [:=> [:cat [:=> [:cat] [:maybe :StructureDb]] [:map-of :string :string]] :GraphAnswer]}
  answer
  "Answer one graph request against whatever `model` yields now: `{:at :answer}`, where `:at` is
   when the model was read and `:answer` is exactly one of `[:graph graph]`, `[:rejected reason]`
   (the request was malformed), `[:unanswerable reason]` (the engine refused the selection or a
   named relation is not binary) or `[:no-model]`."
  [model params]
  (let [db (model)]
    {:at     (str (Instant/now))
     :answer (if-not db
               [:no-model]
               (let [[tag spec open :as parsed] (parse-request db params)]
                 (if (= :rejected tag)
                   parsed
                   (try [:graph (view/view-graph db spec open)]
                        (catch Exception e
                          [:unanswerable (or (ex-message e) (str (class e)))])))))}))

(defn ^{:malli/schema [:=> [:cat [:=> [:cat] [:maybe :StructureDb]]] :ChoicesAnswer]}
  choices
  "Answer what a viewer may nest or link by against whatever `model` yields now: `{:at
   :answer}`, `:answer` exactly one of `[:choices {:relations [{:name :edges}]}]` or
   `[:no-model]`."
  [model]
  (let [db (model)]
    {:at     (str (Instant/now))
     :answer (if db [:choices (view/views db)] [:no-model])}))

(defn ^{:malli/schema [:=> [:cat [:=> [:cat] [:maybe :StructureDb]] [:map-of :string :string]] :DeclarationAnswer]}
  declaration
  "Answer a request for one node's declaration against whatever `model` yields now: `{:at
   :answer}`, `:answer` exactly one of `[:declaration {:id :sort :text}]` — the node's sort and
   its authored form — `[:rejected reason]` when `params` names no node, `[:missing]` when the
   model declares no node with the `id` it names, or `[:no-model]`.

   A node is named by its entity id, as it is in a path. An extracted node is `[:missing]`: it is
   not part of the declared design this namespace serves."
  [model params]
  (let [db (model)
        id (get params "id")]
    {:at     (str (Instant/now))
     :answer (cond
               (not db)        [:no-model]
               (str/blank? id) [:rejected "id: name the node by its entity id"]
               :else
               (let [eid (ffirst (cq/q '[:find ?e :in $ ?id :where [?e :entity/id ?id]] db id))
                     tag (some (fn [[e sort]] (when (= e eid) sort)) (design/declared-nodes db))]
                 (if tag
                   [:declaration {:id id :sort (str tag) :text (inst/instance-text db eid)}]
                   [:missing])))}))

;; ── the transport ─────────────────────────────────────────────────────────────

(defn- query-params
  "A request's query string as a string→string map (last occurrence of a key wins)."
  [{:keys [query-string]}]
  (into {}
        (for [pair (str/split (or query-string "") #"&")
              :when (not (str/blank? pair))
              :let  [[k v] (str/split pair #"=" 2)]]
          [(URLDecoder/decode k "UTF-8") (URLDecoder/decode (or v "") "UTF-8")])))

(defn- json-response [status body]
  {:status  status
   :headers {"Content-Type" "application/json; charset=utf-8" "Cache-Control" "no-store"}
   :body    (json/generate-string body)})

(defn- asset [path content-type]
  (if-let [res (io/resource path)]
    {:status 200 :headers {"Content-Type" content-type "Cache-Control" "no-store"} :body (slurp res)}
    {:status 404 :headers {"Content-Type" "text/plain"} :body "not found"}))

(defn- handler
  [model]
  (fn [{:keys [uri request-method] :as req}]
    (if (not= :get request-method)
      {:status 405 :headers {"Content-Type" "text/plain"} :body "GET only"}
      (case uri
        "/"            (asset "public/explorer.html" "text/html; charset=utf-8")
        "/explorer.js" (asset "public/explorer.js" "text/javascript; charset=utf-8")
        "/api/views"   (json-response 200 (choices model))
        "/api/graph"   (json-response 200 (answer model (query-params req)))
        "/api/node"    (json-response 200 (declaration model (query-params req)))
        {:status 404 :headers {"Content-Type" "text/plain"} :body "not found"}))))

(defn ^{:malli/schema [:=> [:cat :int [:=> [:cat] [:maybe :StructureDb]]] :any]}
  start
  "Serve on `port`, reading the model by calling `model` on every request; `model` may return
   nil, which is answered as no model loaded. Returns the handle `stop` takes."
  [port model]
  (http/run-server (handler model) {:port port :legacy-return-value? false}))

(defn ^{:malli/schema [:=> [:cat :any] :nil]}
  stop
  "Stop a server `start` returned. In-flight requests get 100 ms to finish."
  [server]
  (http/server-stop! server {:timeout 100})
  nil)
