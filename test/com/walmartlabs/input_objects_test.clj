; Copyright (c) 2017-present Walmart, Inc.
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

(ns com.walmartlabs.input-objects-test
  (:require
    [clojure.test :refer [deftest is]]
    [com.walmartlabs.lacinia :as lacinia]
    [com.walmartlabs.lacinia.schema :as schema]
    [com.walmartlabs.test-utils :refer [compile-schema execute expect-exception]]))

(deftest coerce-empty-input-objects-to-empty-hashmaps
  (let [schema (compile-schema "empty-input-objects.edn"
                               {:query/print-input (fn [_ args _]
                                                     (pr-str args))})]
    (is (= {:data {:print_input "{:input {}}"}}
           (execute schema "query { print_input (input: {})}")))))

(deftest null-checks-within-nullable-field
  (let [schema (compile-schema "nested-non-nullable-fields-schema.edn"
                               {:mutation/create-game (fn [_ args _]
                                                        (pr-str args))})]
    (is (= {:data {:create_game "{:game_data {:id 50, :name \"Whist\"}}"}}
           (execute schema "mutation { create_game (game_data: {id: 50, name: \"Whist\"}) }")))

    ;; It's OK to omit the game_data argument entirely
    (is (= {:data {:create_game "nil"}}
           (execute schema "mutation { create_game }")))

    (is (= {:errors [{:extensions {:argument :Mutation/create_game.game_data
                                   :field-name :Mutation/create_game
                                   :missing-key :id
                                   :required-keys [:id]
                                   :schema-type :game_template}
                      :locations [{:column 12
                                   :line 1}]
                      :message "Exception applying arguments to field `create_game': For argument `game_data', no value provided for non-nullable key `id' of input object `game_template'."}]}
           (execute schema "mutation { create_game (game_data: { name: \"Hearts\" }) }")))

    ;; TODO: Missing some needed context from above

    (is (= {:errors [{:extensions {:argument :Mutation/create_game.game_data
                                   :field-name :Mutation/create_game
                                   :missing-key :id
                                   :required-keys [:id]
                                   :schema-type :game_template}
                      :locations [{:column 32
                                   :line 1}]
                      :message "No value provided for non-nullable key `id' of input object `game_template'."}]}
           (execute schema
                    "mutation($g : game_template) { create_game(game_data: $g) }"
                    {:g {:name "Backgammon"}}
                    nil)))))


(deftest allows-for-variables-inside-nested-objects
  (let [schema (compile-schema "input-object-schema.edn"
                               {:queries/search (fn [_ args _]
                                                  [(pr-str args)])})]
    ;; First we make it easy, don't try to make it promote a single value to a list:
    (is (= {:data {:search ["{:filter {:max_count 5, :terms [\"lego\"]}}"]}}
           (execute schema
                    "query($t: [String]) { search(filter: {terms: $t, max_count: 5}) }"
                    {:t ["lego"]}
                    nil)))

    ;; Rejects a literal null in the query parameter

    (is (= "Variable `t' contains null members but supplies the value for a list that can't have any null members."
           (->> (execute schema
                        "query($t: [String]) { search(filter: {terms: $t, max_count: 5}) }"
                        {:t [nil "lego"]}
                        nil)
                :errors
                first
                :message)))

    (is (= "Exception applying arguments to field `search': For argument `filter', an explicit null value was provided for a non-nullable argument."
           (->> (execute schema
                         "{ search(filter: {terms: [null, \"lego\"], max_count: 5}) }"
                         nil
                         nil)
                :errors
                first
                :message)))

    ;; Supports default values for inputs

    (is (= {:data {:search ["{:filter {:terms \"whatsit\", :max_count 10}}"]}}
           (execute schema
                    "query namedQuery($t: String! = \"whatsit\", $c: Int! = 10) {
                      search(filter: {terms: $t, max_count: $c})
                    }")))

    ;; Can handle a variable in the middle of a list

   (is (= {:data {:search ["{:filter {:terms [\"alpha\" \"beta\" \"gamma\"], :max_count 10}}"]}}
           (execute schema
                    "query namedQuery($t: String! = \"beta\", $c: Int! = 10) {
                      search(filter: {terms: [\"alpha\", $t, \"gamma\"], max_count: $c})
                    }")))

    ;; Here we're testing promotion of a single value to a list of that value
    (is (= {:data {:search ["{:filter {:max_count 5, :terms [\"lego\"]}}"]}}
           (execute schema
                    "query($t: String) { search(filter: {terms: $t, max_count: 5}) }"
                    {:t "lego"}
                    nil)))))

(deftest id-type-in-nested-field
  (let [schema (compile-schema "dynamic-input-objects.edn"
                               {:queries/filter (fn [_ args _]
                                                  (pr-str args))})]
    ;; Typical approach:

    (is (= {:data {:filter "{:input {:id \"starwars\", :limit 3}}"}}
           (execute schema
                    "query($id : ID!, $limit: Int!) {
                      filter(input: {id: $id, limit: $limit })
                    }"
                    {:id "starwars"
                     :limit 3}
                    nil)))))


(deftest correct-error-for-unknown-field-in-input-object
  (let [schema (compile-schema "input-object-schema.edn"
                               {:queries/search (fn [_ args _]
                                                  [(pr-str args)])})]
    (is (= {:data {:search ["{:filter {:terms [\"lego\"], :max_count 5}}"]}}
           (execute schema
                    "{ search(filter: {terms: \"lego\", max_count: 5}) }")))

    (is (= {:errors [{:extensions {:argument :Query/search.filter
                                   :field-name :Query/search
                                   :schema-type :Filter}
                      :locations [{:column 3
                                   :line 1}]
                      :message "Exception applying arguments to field `search': For argument `filter', input object contained unexpected key `term'."}]}
           (execute schema
                    "{ search(filter: {term: \"lego\", max_count: 5}) }")))

    #_(is (= {:errors [{:extensions {:argument :Query/search.filter
                                   :field-name :term
                                   :input-object-fields [:max_count
                                                         :terms]
                                   :input-object-type :Filter}
                      :locations [{:column 23
                                   :line 2}]
                      :message "Field not defined for input object."}]}
           (execute schema
                    "query($f : Filter) {
                      search(filter: $f)
                    }"
                    {:f {:term "lego"
                         :max_count 5}}
                    nil)))))

(deftest field-unknown-type
  (expect-exception
    "Field `Insect/legs' references unknown type `Six'."
    {:field-name :Insect/legs
     :schema-types {:input-object [:Insect]
                    :object [:Mutation
                             :Query
                             :Subscription]
                    :scalar [:Boolean
                             :Float
                             :ID
                             :Int
                             :String]}}
    (schema/compile {:input-objects
                     {:Insect
                      {:fields
                       {:legs {:type :Six}}}}})))

(deftest input-object-not-allowed-as-normal-field
  (expect-exception
    "Field `Web/spider' is type `Creepy', input objects may only be used as field arguments."
    {:field-name :Web/spider
     :schema-types {:scalar [:Boolean :Float :ID :Int :String],
                    :object [:Mutation :Query :Subscription :Web],
                    :input-object [:Creepy]}}
    (schema/compile
      {:input-objects
       {:Creepy
        {:fields
         {:legs {:type :Int}}}}
       :objects
       {:Web
        {:fields
         {:spider {:type :Creepy}}}}})))

(deftest object-fields-must-be-scalar-enum-or-input-object
  (expect-exception
    "Field `Turtle/friend' is type `Hare', input objects may only contain fields that are scalar, enum, or input object."
    {:field-name :Turtle/friend
     :schema-types {:scalar [:Boolean :Float :ID :Int :String],
                    :object [:Hare :Mutation :Query :Subscription],
                    :input-object [:Turtle]}}
    (schema/compile
      {:input-objects
       {:Turtle {:fields {:friend {:type :Hare}}}}
       :objects
       {:Hare
        {:fields
         {:speed {:type :Int}}}}})))

(deftest valid-error-when-passing-non-object-to-input-object
  (let [schema (compile-schema "nested-non-nullable-fields-schema.edn"
                               {:mutation/create-game (fn [_ args _]
                                                        (pr-str args))})
        result (lacinia/execute schema "
    mutation ($t: game_template) {
      create_game(game_data: $t)
    }"
                                {:t "<string, not object>"}
                                nil)]
    (is (= {:errors [{:message "Invalid value for input object `game_template'."
                      :extensions {:argument :Mutation/create_game.game_data
                                   :field-name :Mutation/create_game
                                   :input-object-type :game_template
                                   :value "<string, not object>"}
                      :locations [{:line 3
                                   :column 7}]}]}
           result))))
