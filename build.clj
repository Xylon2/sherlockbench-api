(ns build
  (:require [clojure.tools.build.api :as b]))

(def lib 'sherlockbench-api)
(def version (format "1.2.%s" (b/git-count-revs nil)))
(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"}))
;; (def uber-file (format "target/%s-%s-standalone.jar" (name lib) version))
;; make it easier for Ansible to find the file
(def uber-file (format "target/%s.jar" (name lib)))

(defn clean [_]
  (b/delete {:path "target"}))

(defn uber [_]
  (clean nil)
  (b/copy-dir {:src-dirs ["src/clj" "resources" "env/prod/resources"]
               :target-dir class-dir})
  (b/compile-clj {:basis basis
                  :ns-compile '[sherlockbench.core]
                  :class-dir class-dir})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis basis
           :main 'sherlockbench.core}))
