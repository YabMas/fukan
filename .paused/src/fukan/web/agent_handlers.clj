(ns fukan.web.agent-handlers
  "HTTP handlers for /agent/eval and /agent/status.
   Sandboxed eval surface — see fukan.agent.sci."
  (:require [cheshire.core :as json]
            [fukan.agent.sci :as agent-sci]
            [fukan.agent.system :as agent-system]))

(def ^:private response-byte-cap (* 8 1024 1024))  ; 8 MB

(defn- with-byte-cap [body]
  (let [serialised (json/generate-string body)
        n (count (.getBytes serialised "UTF-8"))]
    (if (> n response-byte-cap)
      (json/generate-string
        {:ok? false
         :error/kind :exceeded-cap
         :error/message (str "response body " n " bytes exceeds " response-byte-cap " byte cap")})
      serialised)))

(defn- bad-request [msg]
  {:status 400
   :headers {"Content-Type" "application/json"}
   :body (json/generate-string {:ok? false :error/kind :bad-request :error/message msg})})

(defn handle-eval [req]
  (let [body   (some-> req :body slurp)
        parsed (try (json/parse-string body true) (catch Exception _ nil))
        expr   (:expr parsed)]
    (cond
      (nil? parsed) (bad-request "request body must be JSON {\"expr\":\"…\"}")
      (not (string? expr)) (bad-request "missing :expr")
      :else
      {:status 200
       :headers {"Content-Type" "application/json"}
       :body (with-byte-cap (agent-sci/eval-string expr))})))

(defn handle-status [_req]
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body (json/generate-string {:ok? true :result (agent-system/status)})})
