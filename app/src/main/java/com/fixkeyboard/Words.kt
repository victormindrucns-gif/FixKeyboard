package com.fixkeyboard

/** Small built-in word lists for completions (English, Romanian, Russian), roughly ordered by how common the words are. */
object Words {

    private fun parse(s: String) = s.trim().split(Regex("\\s+")).distinct()

    private val EN = parse("""
        the be to of and a in that have i it for not on with he as you do at this but his by from they we say
        her she or an will my one all would there their what so up out if about who get which go me when make
        can like time no just him know take people into year your good some could them see other than then now
        look only come its over think also back after use two how our work first well way even new want because
        any these give day most us is are was were been has had did does doing going really thanks thank please
        sorry tomorrow today tonight yesterday morning afternoon evening later soon maybe probably definitely
        actually already always never sometimes usually something anything nothing everything someone anyone
        everyone where why while before again still very much many more less little great nice
        happy love hope wish need feel thought knew tell told said call called meet meeting talk
        talking working home house car phone message text send sent let lets okay ok yes yeah sure right
        wrong problem question answer help helping friend family weekend week month birthday party dinner lunch
        breakfast coffee school class office picture photo video number address email excited interesting
        important different possible available beautiful wonderful awesome amazing perfect exactly especially
        appreciate understand remember forget forgot believe honestly basically literally
        should might must weather although though through their there they're your you're its it's don't
        can't won't didn't doesn't isn't wasn't i'm i'll i've we're we'll they'll that's what's let's couldn't
        shouldn't wouldn't haven't hasn't aren't weren't
    """)

    private val RO = parse("""
        și să în nu de la cu pe că mai ce ești este sunt am ai are avem aveți au fost foarte bine bună buna
        mulțumesc mersi te rog scuze scuză îmi pare rău da nu poate acum azi astăzi mâine ieri seară dimineață
        după amiază unde când cum cine ceva nimic totul cineva nimeni mereu niciodată uneori deja încă doar
        chiar destul prea mult puțin mare mic frumos frumoasă super perfect sigur exact adevărat important
        acasă casă mașină telefon mesaj sună sun vorbim vorbesc vin vii venim merg mergem mers plecăm plec ajung
        ajuns aștept așteaptă știu știi cred crezi vreau vrei vrem pot poți putem trebuie trebui facem face
        fac făcut zi zile săptămână weekend lună an prieten prietenă familie mama tata frate soră copii
        mâncare masă cafea cină prânz școală serviciu birou lucru muncă poză video număr adresă bani timp
        oră ore minute întâlnire petrecere ziua aniversare dragă dragul iubire iubesc pupici salut bună ziua
        noapte bună seara spune spus zis zic văd vezi vedem auzi aud înțeleg înțelegi gata ok bine atunci
        pentru despre fiindcă pentru că deoarece dacă însă dar sau ori deci totuși oricum probabil poate
    """)

    private val RU = parse("""
        и в не на я что он с как а то это все она так его но да ты к у же вы за бы по только ее мне было вот
        от меня еще нет о из ему теперь когда даже ну вдруг ли если уже или ни быть был него до вас нибудь
        опять уж вам ведь там потом себя ничего ей может они тут где есть надо ней для мы тебя их чем была
        сам чтоб без будто чего раз тоже себе под будет ж тогда кто этот того потому этого какой совсем ним
        здесь этом один почти мой тем чтобы нее сейчас были куда зачем всех никогда можно при наконец два об
        другой хоть после над больше тот через эти нас про всего них какая много разве три эту моя впрочем
        хорошо свою этой перед иногда лучше чуть том нельзя такой им более всегда конечно всю между
        привет спасибо пожалуйста извини извините прости сегодня завтра вчера утром вечером днем ночью
        сообщение телефон позвони позвоню звонить встреча встретимся работа работаю дома домой машина
        друг подруга семья мама папа брат сестра дети ребенок кофе обед ужин завтрак школа время минут
        час часа неделя выходные месяц день рождения праздник люблю любовь целую обнимаю скучаю понял
        поняла понятно хочу хочешь хотим могу можешь можем знаю знаешь думаю думаешь надеюсь кажется
        наверное вообще правда точно отлично супер классно круто нормально ладно давай договорились жду
        ждешь приеду приду пойдем поедем буду будешь сделаю сделать скажи сказал сказала напиши написал
    """)

    fun complete(prefix: String, lang: String, max: Int = 3): List<String> {
        if (prefix.isEmpty()) return emptyList()
        val p = prefix.lowercase()
        val list = when (lang) { "ro" -> RO; "ru" -> RU; else -> EN }
        val hits = list.filter { it.startsWith(p) && it != p }.take(max)
        // keep the user's capitalization of the first letter
        return if (prefix[0].isUpperCase()) hits.map { it.replaceFirstChar { c -> c.uppercase() } } else hits
    }
}
