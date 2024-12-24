(ns sherlockbench.queries
  ;; exclude these core clojure functions
  (:refer-clojure :exclude [distinct filter for group-by into partition-by set update])
  
  (:require [honey.sql :as sql]
            [honey.sql.helpers :refer :all]  ;; shadows core functions
            [clojure.core :as c]  ;; so we can still access core functions
            [next.jdbc :as jdbc]
            [buddy.hashers :as hashers]
            [clojure.data.json :as json]))

;; the core namespace will closure over this with the connection
(defn execute-query
  "takes a connection, one or more HoneySQL queries, and a post-processor function"
  [conn qdata & [debug]]
  (let [[processor & queries] (reverse qdata)
        formatted-queries (map sql/format (reverse queries))]
    (when debug (doseq [formatted-query formatted-queries]
                    (println (str "formatted-query: " formatted-query))))

    (apply processor (map #(jdbc/execute! conn %) formatted-queries))))

(defn create-user
  "hashes the password"
  [login password]
  [(-> (insert-into :users)
       (values [{:login login
                 :password (hashers/derive password)}]))

   identity])

(defn authenticate-user
  "we return true or false indicating authentication success"
  [login password]
  [(-> (select [:*])
       (from :users)
       (where [:= :login login]))

   (fn [[{hashed :users/password}]]
     (hashers/check password hashed))])

(defn create-run!
  [benchmark-version msg-limit]
  [(-> (insert-into :runs)
       (values [{:benchmark_version benchmark-version
                 :config [:cast (json/write-str {:msg-limit msg-limit}) :jsonb]}])
       (returning :id))

   #(:runs/id (first %))])

(defn create-attempt!
  [run-id problem]
  [(-> (insert-into :attempts)
       (values [{:run_id run-id
                 :function_name (:name- problem)
                 :verifications [:cast (json/write-str (:verifications problem)) :jsonb]}])
       (returning :id))

   #(:attempts/id (first %))])

(defn check-run
  "return true or false indicating if they have a valid run_id"
  [run-id]
  [(-> (select [:*])
       (from :runs)
       (where [:and
               [:= :id [:cast run-id :uuid]]
               [:> [:+ :datetime_start [:interval "1 day"]] [:now]]
               [:= :final_score nil]]))

   #(not (empty? %))])

(defn get-fn-name
  "Given an attempt UUID, returns the associated function_name."
  [attempt-id]
  [(-> (select [:function_name])
       (from :attempts)
       (where [:= :id [:cast attempt-id :uuid]]))

   #(:attempts/function_name (first %))])

(defn attempt-valid?
  "given both a run id and attempt id, check the attempt id matches to the run"
  [run-id attempt-id]
  [(-> (select [:*])
       (from :attempts)
       (where [:and
               [:= :run-id [:cast run-id :uuid]]
               [:= :id [:cast attempt-id :uuid]]]))

   #(not (empty? %))])

(defn increment-fn-calls
  "Increments the fn_calls column for a given attempt ID."
  [attempt-id]
  [(-> (update :attempts)
       (set {:fn_calls [:+ :fn_calls 1]})
       (where [:= :id [:cast attempt-id :uuid]])
       (returning :fn_calls))

   #(:attempts/fn_calls (first %))])

(defn started-verifications?
  "Given an attempt UUID, returns if it started verifications yet."
  [attempt-id]
  [(-> (select [:started_verifications])
       (from :attempts)
       (where [:= :id [:cast attempt-id :uuid]]))

   #(:attempts/started_verifications (first %))])

(defn get-verifications
  "Given an attempt UUID, returns the associated validations"
  [attempt-id]
  [(-> (select [:verifications])
       (from :attempts)
       (where [:= :id [:cast attempt-id :uuid]]))

   #(json/read-str (.getValue (:attempts/verifications (first %))))])

(defn started-verifications!
  "Given an attempt UUID, set the started_verifications field."
  [attempt-id]
  [(-> (update :attempts)
       (set {:started_verifications true})
       (where [:= :id [:cast attempt-id :uuid]]))

   identity])

(defn save-verifications!
  "Given an attempt UUID, set the started_verifications field."
  [attempt-id v]
  [(-> (update :attempts)
       (set {:verifications [:cast (json/write-str v) :jsonb]})
       (where [:= :id [:cast attempt-id :uuid]]))

   identity])

(defn attempt-failure!
  "Given an attempt UUID, set the attempt failed."
  [attempt-id]
  [(-> (update :attempts)
       (set {:result_value "failure"
             :verifications [:cast (json/write-str nil) :jsonb]})
       (where [:= :id [:cast attempt-id :uuid]]))

   identity])

(defn attempt-success!
  "Given an attempt UUID, set the attempt succeeded."
  [attempt-id]
  [(-> (update :attempts)
       (set {:result_value "success"})
       (where [:= :id [:cast attempt-id :uuid]]))

   identity])

(defn get-run-time
  [run-id]
  [(-> (select :datetime_start [[:age [:now] :datetime_start] :total_run_time])
       (from :runs)
       (where [:= :id [:cast run-id :uuid]]))

   #(:total_run_time (first %))])

(defn get-final-score
  [run-id]
  [(-> (select [[:count :*] :count])
       (from :attempts)
       (where [:and
               [:= :run_id [:cast run-id :uuid]]
               [:= :result_value "success"]]))

   (-> (select [[:count :*] :count])
       (from :attempts)
       (where [:= :run_id [:cast run-id :uuid]]))

   #(hash-map :numerator (:count (first %1)) :denominator (:count (first %2)))])

(defn save-results!
  [run-id total-run-time final-score score-percent]
  [(-> (update :runs)
       (set {:total_run_time total-run-time
             :final_score [:cast (json/write-str final-score) :jsonb]
             :score_percent score-percent})
       (where [:= :id [:cast run-id :uuid]]))

   identity])
