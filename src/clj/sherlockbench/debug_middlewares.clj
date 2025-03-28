(ns sherlockbench.debug-middlewares
  (:require [clojure.string :as string]))

(defn wrap-debug-reqmap
  "debug middleware to save the requestmap to a file so we can analyze"
  [handler comment]
  (fn [req]
    (when-not (string/includes? (:uri req) "favicon.ico")
      (let [timestamp (.toString (java.time.LocalDateTime/now))
            filename (str "reqlog/request-" comment "-" timestamp ".edn")]
        ;;(clojure.pprint/pprint (dissoc req :reitit.core/match :reitit.core/router) (clojure.java.io/writer filename))
        (clojure.pprint/pprint req (clojure.java.io/writer filename))
        ))

    ; Call the handler and return its response
    (handler req)))

(defn whenwrap
  "debug middleware to remind myself what order the middlewares run"
  [handler comment]
  (fn [request]
    (println comment)
    (handler request)))

(defn log-path-middleware
  "Middleware that logs the request path."
  [handler]
  (fn [request]
    (let [path (:uri request)]
      (println "Path hit:" path))
    (handler request)))
