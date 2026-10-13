package app.talktype

import org.junit.Assert.assertEquals
import org.junit.Test

class NumbersTest {
    private fun check(spoken: String, expected: String) = assertEquals(expected, Numbers.toDigits(spoken))

    @Test fun basics() {
        check("I have two kids and twenty five chickens.", "I have 2 kids and 25 chickens.")
        check("Twelve people came", "12 people came")
        check("it costs three hundred and five dollars", "it costs \$305")
        check("about one hundred fifty thousand", "about 150,000")
        check("twenty-five percent", "25%")
        check("two thousand twenty six", "2026")
        check("a hundred times", "100 times")
        check("nineteen hundred", "1900")
        check("zero issues", "0 issues")
        check("already 15 of them", "already 15 of them")
    }

    @Test fun backToBack() {
        check("we need two twelve inch pipes", "we need two 12 inch pipes")
        check("grab three five-gallon buckets", "grab three 5-gallon buckets")
        check("ten twenty foot sections", "ten 20 foot sections")
    }

    @Test fun yearsAndTimes() {
        check("back in nineteen ninety nine", "back in 1999")
        check("twenty twenty six budget", "2026 budget")
        check("in twenty ten", "in 2010")
        check("meet at seven thirty", "meet at 7:30")
        check("ten fifteen pm works", "10:15 pm works")
        check("call me at five pm", "call me at 5 pm")
    }

    @Test fun digitStrings() {
        check("call five five five one two one two", "call 5551212")
        check("dial nine one one", "dial 911")
        check("area code eight oh one", "area code 801")
    }

    @Test fun decimalsOrdinalsFractions() {
        check("three point five miles", "3.5 miles")
        check("zero point two five", "0.25")
        check("the twenty third of May", "the 23rd of May")
        check("the first one", "the first one")
        check("two thirds of it", "two thirds of it")
        check("three quarters done", "three quarters done")
        check("give me twenty seconds", "give me 20 seconds")
    }

    @Test fun one() {
        check("this one is good", "this one is good")
        check("no one came", "no one came")
        check("one of them", "one of them")
        check("one percent", "1%")
        check("page one", "page 1")
        check("one or two days", "1 or 2 days")
        check("one, two, three", "1, 2, 3")
        check("five to one", "5 to 1")
        check("one hundred", "100")
        check("twenty one", "21")
    }

    @Test fun symbols() {
        check("up ten percent from 40 percent", "up 10% from 40%")
        check("three point five percent", "3.5%")
        check("a hundred percent", "100%")
        check("one dollar", "\$1")
        check("five million dollars", "\$5,000,000")
        check("five dollars and fifty cents", "\$5.50")
        check("twelve dollars and five cents", "\$12.05")
        check("a few dollars and fifty cents", "a few dollars and 50 cents")
        check("two twelve dollar items", "two \$12 items")
    }

    @Test fun leavesOrdinaryWordsAlone() {
        check("I want to go too, for sure.", "I want to go too, for sure.")
        check("someone and anyone", "someone and anyone")
        check("thanks a million", "thanks a million")
        check("a nice day", "a nice day")
        check("", "")
    }
}
