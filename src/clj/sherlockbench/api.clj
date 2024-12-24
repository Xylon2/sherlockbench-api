(ns sherlockbench.api
  (:require [sherlockbench.config :refer [benchmark-version msg-limit]]
            [sherlockbench.queries :as q]
            [sherlockbench.problems :refer [problems]]
            [sherlockbench.validate-fn-args :refer [validate-and-coerce]]
            [clojure.data.json :as json]))

(defn start-anonymous-run
  "initialize database entries for an anonymous run"
  [{queryfn :queryfn}]
  (let [; get the pertinent subset of the problems and randomize the order
        problems' (shuffle (filter #(:demo (:tags %)) problems))
        run-id (queryfn (q/create-run! benchmark-version msg-limit))
        attempts (for [p problems'      ; 1 attempt per problem
                       :let [attempt (queryfn (q/create-attempt! run-id p))]]
                   {:attempt-id attempt
                    :fn-args (:args p)})]

   {:status 200
    :headers {"Content-Type" "application/json"}
    :body {:run-id run-id
           :attempts attempts}}))

(defn wrap-check-run
  "did they give us a valid run id?"
  [handler]
  (fn [{queryfn :queryfn
        {:keys [run-id]} :body :as request}]
    (if (queryfn (q/check-run run-id))
      ;; continue as if nothing happened
      (handler request)

      ;; break as they have an expired session
      {:status 412
       :headers {"Content-Type" "application/json"}
       :body {:error "your run appears to be invalid or expired"}})))

(defn wrap-check-attempt
  "did they give us a valid attempt id?"
  [handler]
  (fn [{queryfn :queryfn
        {:keys [run-id attempt-id]} :body :as request}]
    (if (queryfn (q/attempt-valid? run-id attempt-id))
      ;; add the fn-name to the request as it's handy later
      (let [fn-name (queryfn (q/get-fn-name attempt-id))]
        (handler (assoc request :fn-name fn-name)))

      ;; break as they have an expired session
      {:status 412
       :headers {"Content-Type" "application/json"}
       :body {:error "your attempt-id doesn't match your run-id"}})))

(defn get-problem-by-name
  [fn-name]
  (first (filter #(= fn-name (:name- %)) problems)))

(defn wrap-validate-args
  "a middleware to validate the args of a test function"
  [handler]
  (fn [{queryfn :queryfn
        {:keys [run-id attempt-id args]} :body
        fn-name :fn-name :as request}]
    (let [this-problem (get-problem-by-name fn-name)
          {:keys [valid? coerced]} (validate-and-coerce (:args this-problem) args)]
      (if valid?
        ;; add the validated args and continue
        (handler (assoc request
                        :validated-args coerced
                        :fn-name fn-name))

        ;; break as these args are invalid
        {:status 400
         :headers {"Content-Type" "application/json"}
         :body {:error "your arguments don't comply with the schema"}}))))

(defn apply-fn
  [problem validated-args]
  (try (apply (:function problem) validated-args)
       (catch Exception e
         "Exception")))

(defn test-function
  "run the test"
  [{queryfn :queryfn
    validated-args :validated-args
    fn-name :fn-name
    {:keys [attempt-id]} :body}]

  (let [call-count (queryfn (q/increment-fn-calls attempt-id))
        started-verifications (queryfn (q/started-verifications? attempt-id))]
    (cond
      (> call-count msg-limit)
      {:status 400
       :headers {"Content-Type" "application/json"}
       :body {:error (format "you have reached the test limit of %d for this problem" msg-limit)}}
      (true? started-verifications)
      {:status 400
       :headers {"Content-Type" "application/json"}
       :body {:error "you cannot test the function after you start the validations"}}
      
      :else
      (let [problem (get-problem-by-name fn-name)
            output (apply-fn problem validated-args)]

        {:status 200
         :headers {"Content-Type" "application/json"}
         :body {:output output}}))))

(defn wrap-record-started [handler]
  (fn [{queryfn :queryfn
        {:keys [attempt-id]} :body :as request}]

    (queryfn (q/started-verifications! attempt-id))

    (handler request)))

(defn pop-verification
  [queryfn attempt-id]
  (let [[this & rest] (queryfn (q/get-verifications attempt-id))]
    (queryfn (q/save-verifications! attempt-id rest))
    [this rest]))

(defn next-verification
  "just give them the next verification for their attempt"
  [{queryfn :queryfn
    fn-name :fn-name
    {:keys [attempt-id]} :body}]

  (queryfn (q/started-verifications! attempt-id)) ; record we've started

  (let [next-verification (first (queryfn (q/get-verifications attempt-id)))]
    (if (nil? next-verification)
      {:status 200
       :headers {"Content-Type" "application/json"}
       :body {:status "done"
              :next-verification nil}}
      {:status 200
       :headers {"Content-Type" "application/json"}
       :body {:status "success"
              :next-verification next-verification}})))

(defn =normalized
  "normalize everything to a string for comparrison"
  [& all]
  (apply = (map str all)))

(defn attempt-verification
  "let the client attempt a verification"
  [{queryfn :queryfn
    fn-name :fn-name
    {:keys [attempt-id prediction]} :body}]

  (let [problem (get-problem-by-name fn-name)
        [this-verification remaining-verifications] (pop-verification queryfn attempt-id)]
    (if (nil? this-verification)
      {:status 400
       :headers {"Content-Type" "application/json"}
       :body {:error "you're done"}}
      (let [output (apply-fn problem this-verification)]
        (if (=normalized prediction output)
          (if remaining-verifications
            ;; success with more
            {:status 200
             :headers {"Content-Type" "application/json"}
             :body {:status "correct"
                    :next-verification (first remaining-verifications)}}

            ;; all done
            (do
              (queryfn (q/attempt-success! attempt-id))
              {:status 200
               :headers {"Content-Type" "application/json"}
               :body {:status "done"
                      :next-verification nil}})
            )
          ;; failure
          (do
            (queryfn (q/attempt-failure! attempt-id))
            {:status 200
             :headers {"Content-Type" "application/json"}
             :body {:status "done"
                    :next-verification nil}}))))))
