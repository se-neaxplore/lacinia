; Copyright (c) 2021-present Walmart, Inc.
;
; Licensed under the Apache License, Version 2.0 (the "License")
; you may not use this file except in compliance with the License.
; You may obtain a copy of the License at
;
;     http://www.apache.org/licenses/LICENSE-2.0
;
; Unless required by applicable law or agreed to in writing, software
; distributed under the License is distributed on an "AS IS" BASIS,
; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
; See the License for the specific language governing permissions and
; limitations under the License.

;; clj -T:build <var>

(ns build
  (:require [clojure.string :as string]
            [clojure.tools.build.api :as bb]
            [deps-deploy.deps-deploy :as dd]
            [net.lewisship.build :as b]))

(def lib 'com.neax/lacinia)
(def version (-> "VERSION.txt" slurp string/trim))

(def jar-params {:project-name lib
                 :version version})
(def basis (bb/create-basis {:project "deps.edn"}))

(def class-dir "target/classes")

(defn- jar-opts [opts]
  (assoc opts
         :lib lib :version version
         :jar-file (format "target/%s-%s.jar" lib version)
         :scm {:tag (str "v" version)}
         :basis (bb/create-basis {})
         :class-dir class-dir
         :target "target"
         :src-dirs ["src"]))

(defn clean
  [_params]
  (bb/delete {:path "target"}))

(defn jar
  [_params]
  (b/create-jar jar-params))

(defn codox
  [_params]
  (b/generate-codox {:project-name lib
                     :version version
                     :aliases [:dev]}))

(defn compile-ns [opts]
  (bb/compile-clj {:basis basis
                  :src-dirs ["src"]
                  :class-dir class-dir
                  :compile-opts {:direct-linking true
                                 :elide-meta [:doc :file :line :added]}}))

(defn ci "Run the CI pipeline of tests (and build the JAR)." [opts]
  (bb/delete {:path "target"})
  (let [opts (jar-opts opts)]
    (println "\nWriting pom.xml...")
    (bb/write-pom opts)
    (println "\nCopying source...")
    (bb/copy-dir {:src-dirs ["resources" "src"] :target-dir class-dir})
    (compile-ns nil)
    (println "\nBuilding JAR...")
    (bb/jar opts))
  opts)

(defn deploy "Deploy the JAR to Github." [opts]
  (let [{:keys [jar-file] :as opts} (jar-opts opts)]
    (dd/deploy {:installer :remote :artifact (bb/resolve-path jar-file)
                :repository repo-settings
                :pom-file (bb/pom-path (select-keys opts [:lib :class-dir]))}))
  opts)

(defn publish
  "Generate Codox documentation and publish via a GitHub push."
  [_params]
  (println "Generating Codox documentation")
  (codox nil))
