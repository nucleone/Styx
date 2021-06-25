package com.jamal2367.styx.adblock

import com.jamal2367.styx.adblock.core.ContentRequest
import com.jamal2367.styx.adblock.filter.unified.*
import com.jamal2367.styx.database.adblock.UserRulesRepository
import javax.inject.Inject
import javax.inject.Singleton

/*
    user rules:
    implements what is called 'dynamic filtering' in ubo: https://github.com/gorhill/uBlock/wiki/Dynamic-filtering:-quick-guide
    uses only 2 types of filters and a special filter container working with pageUrl.host as tag (empty for global rules)
    each rule is a single filter! this is important for easy display of existing filter rules
     -> TODO: test how much slower it is compared to optimized filters
          e.g. global block of example.net and example.com could be one filter with both domains in the domainMap
          but when adding/removing rules, it would be necessary to remove the filter, and add a new one with modified domainMap
          definitely possible, but is it worth the work?
          also content types could be joined
    TODO:
     test how long loading takes, if slow find some solution
     could the db operations for add/remove introduce noticeable delays when done on the wrong thread?
      probably safer to do on IO thread anyway...
 */

@Singleton
class AbpUserRules @Inject constructor(
    private val userRulesRepository: UserRulesRepository
){

    private val userRules by lazy { UserFilterContainer().also{ userRulesRepository.getAllRules().forEach(it::add) } }
//    private lateinit var userRules: UserFilterContainer
//    private val userRules = UserFilterContainer()

    init {
        // TODO: maybe move to background?
        //  try:
        //   by lazy: may needlessly delay first request -> by how much?
        //   load blocking: may block for too long -> how long?
        //   load in background -> need to check every time whether it's already loaded
//        loadUserLists()
    }

    private fun loadUserLists() {
        // on S4 mine: takes 150 ms for the first time, then 6 ms for empty db
        val ur = userRulesRepository.getAllRules()

        // careful: the following line crashes (my) android studio:
//        userRules = UserFilterContainer().also { ur.forEach(it::add) } }
        // why? it's the same way used for 'normal' filter containers

//        userRules = UserFilterContainer()
        ur.forEach { userRules.add(it) }
    }

    // true: block
    // false: allow
    // null: nothing (relevant to supersede more general block/allow rules)
    fun getResponse(contentRequest: ContentRequest): Boolean? {
        return userRules.get(contentRequest)?.response
    }

    fun addUserRule(filter: UnifiedFilterResponse) {
        userRules.add(filter)
        userRulesRepository.addRules(listOf(filter))
    }

    fun removeUserRule(filter: UnifiedFilterResponse) {
        userRules.remove(filter)
        userRulesRepository.removeRule(filter)
    }

    /*
        some examples
        entire page: <page domain>, "", 0xffff, false
        everything from youtube.com: "", "youtube.com", 0xffff, false
        everything 3rd party from youtube.com: "", "youtube.com", 0xffff, true
        all 3rd party frames: "", "", ContentRequest.TYPE_SUB_DOCUMENT, true //TODO: should be sub_document, not checked
        find content types in ContentRequest, and how to get it from request in AdBlock -> WebResourceRequest.getContentType
    */

    fun createUserFilter(pageDomain: String, requestDomain: String, contentType: Int, thirdParty: Boolean): UnifiedFilter {
        // 'domains' contains (usually 3rd party) domains, but can also be same as pageDomain (or subdomain of pageDomain)
        // include is always set to true (filter valid only on this domain, and any subdomain if there is no more specific rule)
        val domains = if (requestDomain.isNotEmpty())
            SingleDomainMap(true, requestDomain)
        else null

        // thirdParty true means filter only applied to 3rd party content, translates to 1 in the filter
        //  0 would be only first party, -1 is for both
        //  maybe implement 0 as well, but I think it's not used in ublock (any why would i want to block 1st, but not 3rd party stuff?)
        val thirdPartyInt = if (thirdParty) 1 else -1

        // ContainsFilter for global rules (empty pattern), HostFilter for local rules
        return if (pageDomain.isEmpty())
            ContainsFilter(pageDomain, contentType, domains, thirdPartyInt)
        else
            HostFilter(pageDomain, contentType, false, domains, thirdPartyInt)
    }

    fun addUserRule(pageDomain: String, requestDomain: String, contentType: Int, thirdParty: Boolean, response: Boolean?) {
        addUserRule(UnifiedFilterResponse(createUserFilter(pageDomain, requestDomain, contentType, thirdParty), response))
    }

    fun removeUserRule(pageDomain: String, requestDomain: String, contentType: Int, thirdParty: Boolean, response: Boolean?) {
        removeUserRule(UnifiedFilterResponse(createUserFilter(pageDomain, requestDomain, contentType, thirdParty), response))
    }

}
