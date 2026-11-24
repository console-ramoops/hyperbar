//! Presentation states recovered from MIUIDeskClock NotificationUtil and the
//! original compact/expanded model contracts. Android supplies state and data.

/// Output: expanded asset, compact asset, small asset, expanded type,
/// autoplay, right grammar (0 counter, 1 fixed time, 2 supplied text).
pub fn plan(style:i32,count_down:bool,paused:bool,has_time:bool)->Option<[i32;6]>{
    match style {
        0=>{let asset=if count_down {0}else{1};Some([asset,asset,asset,2,i32::from(!paused),if has_time {0}else{2}])},
        1=>Some([2,2,2,2,1,if has_time {1}else{2}]),
        2=>Some([2,2,2,2,0,if has_time {1}else{2}]),
        3=>Some([0,0,0,0,0,2]),
        _=>None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn original_provider_states(){
        assert_eq!(plan(0,true,false,true),Some([0,0,0,2,1,0]));
        assert_eq!(plan(0,true,true,true),Some([0,0,0,2,0,0]));
        assert_eq!(plan(0,false,false,true),Some([1,1,1,2,1,0]));
        assert_eq!(plan(0,false,true,true),Some([1,1,1,2,0,0]));
        assert_eq!(plan(1,false,false,true),Some([2,2,2,2,1,1]));
        assert_eq!(plan(2,false,false,true),Some([2,2,2,2,0,1]));
        assert_eq!(plan(3,true,false,true),Some([0,0,0,0,0,2]));
    }
    #[test]
    fn missing_source_times_are_not_counters(){
        for style in 0..=3 {assert_eq!(plan(style,false,false,false).unwrap()[5],2);}
        assert_eq!(plan(-1,false,false,false),None);assert_eq!(plan(4,false,false,false),None);
    }
}
