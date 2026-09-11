(ns transportclerk.advisor
  "TransportClerksAdvisor — proposes a manifest operation (approve a
  manifest, approve an overweight permit) for a registered
  organization. Swappable mock/llm; the advisor ONLY proposes —
  `transportclerk.governor` checks the payload ceiling and hazmat
  class authorization independently. Modeled on
  cloud-itonami-isco-4311's advisor.

  A proposal: {:op :approve-manifest|:approve-overweight-permit
               :effect :propose :vehicle-id str :total-weight-kg number
               :hazmat-classes #{str} :stake kw :confidence n
               :rationale str}"
  (:require #?(:clj [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])))

(defprotocol Advisor
  (-advise [advisor store request] "request -> proposal map"))

(defn- infer [_store {:keys [op stake vehicle-id total-weight-kg hazmat-classes] :as request}]
  {:op op
   :effect :propose
   :vehicle-id vehicle-id
   :total-weight-kg total-weight-kg
   :hazmat-classes hazmat-classes
   :stake (or stake :low)
   :confidence (case (or stake :low) :high 0.7 :medium 0.85 :low 0.95)
   :rationale (str "proposed " (name op) " for client " (:client-id request))})

(defn mock-advisor []
  (reify Advisor
    (-advise [_ store request] (infer store request))))

(def ^:private system-prompt
  "You are a transport clerk advisor. Given a request, propose an
   :op, the :vehicle-id, :total-weight-kg and :hazmat-classes, an
   honest :confidence and a :stake. Never call an over-payload
   manifest or an unauthorized hazmat class conforming — the governor
   checks both against the registered vehicle record.")

(defn- parse-proposal [content]
  (try
    (let [p (edn/read-string content)]
      (if (map? p)
        (assoc p :effect :propose)
        {:op :unknown :effect :propose :confidence 0.0 :stake :high
         :rationale "unparseable LLM response"}))
    (catch #?(:clj Exception :cljs js/Error) _
      {:op :unknown :effect :propose :confidence 0.0 :stake :high
       :rationale "LLM response parse failure"})))

(defn llm-advisor
  [chat-model model-generate-fn gen-opts]
  (reify Advisor
    (-advise [_ _store request]
      (let [msgs [{:role :system :content system-prompt}
                  {:role :user :content (str "operation request: " (pr-str request))}]
            resp (model-generate-fn chat-model msgs gen-opts)]
        (parse-proposal (:content resp))))))
